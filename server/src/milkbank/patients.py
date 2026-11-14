"""환자 서비스: 등록, 수정, 조회, 전화번호 정규화, 병상 정렬."""
import re

from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .clock import KST, Clock
from .errors import BadRequest, Conflict, NotFound
from .models import Patient, PatientStatus
from .schemas import PatientCreate, PatientUpdate

_DUP_REG_NO = "이미 등록된 등록번호입니다"
_REQUIRED = {
    "reg_no": "등록번호",
    "baby_name": "아기 이름",
    "bed": "병상",
    "guardian_name": "보호자 이름",
}


def normalize_phone(raw: str) -> str:
    digits = re.sub(r"\D", "", raw, flags=re.ASCII)
    if not re.fullmatch(r"01\d{8,9}", digits, flags=re.ASCII):
        raise BadRequest("전화번호 형식이 올바르지 않습니다")
    return digits


def bed_sort_key(bed: str) -> tuple:
    # 숫자 조각은 (0, 정수), 문자 조각은 (1, 문자열)로 만들어 타입이 섞여도 비교 가능하게 한다.
    return tuple(
        (0, int(part), "") if part.isdecimal() and part.isascii() else (1, 0, part)
        for part in re.split(r"(\d+)", bed, flags=re.ASCII)
        if part != ""
    )


def _clean(values: dict) -> dict:
    out = dict(values)
    for key, label in _REQUIRED.items():
        if key in out:
            out[key] = (out[key] or "").strip()
            if not out[key]:
                raise BadRequest(f"{label}을(를) 입력해 주세요")
    if out.get("reg_no", "").startswith("말소-"):
        raise BadRequest("‘말소-’로 시작하는 등록번호는 쓸 수 없습니다")
    if "guardian_phone" in out:
        out["guardian_phone"] = normalize_phone(out["guardian_phone"])
    return out


def _reg_no_taken(db: Session, reg_no: str, except_id: int | None = None) -> bool:
    stmt = select(Patient.id).where(Patient.reg_no == reg_no)
    if except_id is not None:
        stmt = stmt.where(Patient.id != except_id)
    return db.scalar(stmt) is not None


def create_patient(db: Session, clock: Clock, data: PatientCreate) -> Patient:
    values = _clean(data.model_dump())
    if _reg_no_taken(db, values["reg_no"]):
        raise Conflict(_DUP_REG_NO)
    if values["admitted_on"] is None:
        values["admitted_on"] = clock.now().astimezone(KST).date()
    patient = Patient(**values, status=PatientStatus.ADMITTED, created_at=clock.now())
    db.add(patient)
    try:
        db.flush()
    except IntegrityError:
        raise Conflict(_DUP_REG_NO) from None
    return patient


def update_patient(db: Session, patient_id: int, data: PatientUpdate) -> Patient:
    patient = get_patient(db, patient_id)
    if patient.erased_at is not None:
        raise Conflict("말소된 환자는 수정할 수 없습니다")
    values = data.model_dump(exclude_unset=True)
    # 필수 필드와 날짜·알림 사용 여부는 null로 지울 수 없다(보내지 않은 것으로 본다).
    for key in (*_REQUIRED, "guardian_phone", "admitted_on", "alert_enabled"):
        if key in values and values[key] is None:
            del values[key]
    values = _clean(values)
    if "reg_no" in values and _reg_no_taken(db, values["reg_no"], patient.id):
        raise Conflict(_DUP_REG_NO)
    for key, value in values.items():
        setattr(patient, key, value)
    try:
        db.flush()
    except IntegrityError:
        raise Conflict(_DUP_REG_NO) from None
    return patient


def get_patient(db: Session, patient_id: int) -> Patient:
    patient = db.get(Patient, patient_id)
    if patient is None:
        raise NotFound("환자를 찾을 수 없습니다")
    return patient


def list_patients(db: Session, q: str | None, include_discharged: bool) -> list[Patient]:
    stmt = select(Patient)
    if not include_discharged:
        stmt = stmt.where(Patient.status == PatientStatus.ADMITTED)
    q = (q or "").strip()
    if q:
        like = f"%{q.replace(chr(92), chr(92) * 2).replace('%', chr(92) + '%').replace('_', chr(92) + '_')}%"
        stmt = stmt.where(
            or_(
                Patient.baby_name.like(like, escape="\\"),
                Patient.bed.like(like, escape="\\"),
                Patient.reg_no.like(like, escape="\\"),
            )
        )
    return sorted(db.scalars(stmt), key=lambda p: bed_sort_key(p.bed))
