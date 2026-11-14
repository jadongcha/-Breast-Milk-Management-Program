"""퇴원 처리와 개인정보 말소.

모든 트랜잭션은 BEGIN IMMEDIATE로 시작하므로 각 함수의 첫 쿼리가 쓰기 잠금을 잡는다.
"""
from datetime import timedelta
from typing import Literal

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from . import settings as settings_service
from . import stock
from .clock import Clock
from .errors import BadRequest, Conflict, NotFound
from .models import (
    DiscardReason,
    GuardianSession,
    Patient,
    PatientStatus,
    RecordKind,
    Staff,
    StockRecord,
    Submission,
)
from .reports import kst_today


def discharge(
    db: Session,
    clock: Clock,
    *,
    patient_id: int,
    remaining: Literal["return", "discard"],
    discard_reason: DiscardReason | None,
    staff: Staff,
) -> Patient:
    patient = db.get(Patient, patient_id)  # 첫 쿼리: 쓰기 잠금
    if patient is None:
        raise NotFound("환자를 찾을 수 없습니다")
    if patient.status == PatientStatus.DISCHARGED:
        raise Conflict("이미 퇴원한 환자입니다")
    left = stock.balance(db, patient_id)
    if left > 0:
        # 상태를 바꾸기 전에 기록한다(퇴원 환자에게는 기록을 추가할 수 없다).
        if remaining == "discard":
            if discard_reason is None:
                raise BadRequest("폐기 사유를 선택해 주세요")
            kind, reason = RecordKind.DISCARD, discard_reason
        else:
            kind, reason = RecordKind.RETURN, None
        stock.add_record(
            db, clock, patient_id=patient_id, kind=kind, count=left,
            staff=staff, discard_reason=reason,
        )
    patient.status = PatientStatus.DISCHARGED
    patient.discharged_on = kst_today(clock)
    db.flush()
    still_admitted = db.scalar(
        select(Patient.id).where(
            Patient.guardian_phone == patient.guardian_phone,
            Patient.status == PatientStatus.ADMITTED,
        ).limit(1)
    )
    if still_admitted is None:
        db.execute(
            delete(GuardianSession).where(GuardianSession.phone == patient.guardian_phone)
        )
    return patient


def erase(db: Session, clock: Clock, *, patient_id: int) -> Patient:
    patient = db.get(Patient, patient_id)  # 첫 쿼리: 쓰기 잠금
    if patient is None:
        raise NotFound("환자를 찾을 수 없습니다")
    if patient.status != PatientStatus.DISCHARGED:
        raise Conflict("퇴원한 환자만 말소할 수 있습니다")
    if patient.erased_at is not None:
        raise Conflict("이미 말소된 환자입니다")
    patient.baby_name = f"퇴원환자-{patient.id}"
    patient.reg_no = f"말소-{patient.id}"
    patient.guardian_name = ""
    patient.guardian_phone = ""
    db.execute(
        update(StockRecord).where(StockRecord.patient_id == patient.id).values(memo=None)
    )
    db.execute(
        update(Submission).where(Submission.patient_id == patient.id).values(memo=None)
    )
    patient.erased_at = clock.now()
    db.flush()
    return patient


def erase_due(db: Session, clock: Clock) -> list[int]:
    retention = settings_service.get(db).retention_days  # 첫 쿼리: 쓰기 잠금
    today = kst_today(clock)
    candidates = db.scalars(
        select(Patient).where(
            Patient.status == PatientStatus.DISCHARGED,
            Patient.erased_at.is_(None),
            Patient.discharged_on.is_not(None),
        ).order_by(Patient.id)
    ).all()
    done = []
    for p in candidates:
        if today >= p.discharged_on + timedelta(days=retention):
            erase(db, clock, patient_id=p.id)
            done.append(p.id)
    return done
