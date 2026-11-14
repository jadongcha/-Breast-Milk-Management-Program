"""제출 서비스: 보호자 제출, 확인 대기 목록, 간호사의 확인·반려.

모든 트랜잭션은 BEGIN IMMEDIATE로 시작하므로 각 함수의 첫 쿼리가 쓰기 잠금을 잡는다.
confirm/reject는 첫 쿼리가 제출 조회라서, 같은 제출을 동시에 처리해도 한쪽만 통과한다.
"""
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import stock
from .clock import Clock
from .config import Config
from .errors import BadRequest, Conflict, Forbidden, NotFound
from .models import (
    GuardianSession,
    Patient,
    PatientStatus,
    RecordKind,
    RejectReason,
    Staff,
    Station,
    StockRecord,
    Submission,
    SubmissionStatus,
)

GENERAL_DEVICE_NAME = "병원 기기"


def submit(
    db: Session, clock: Clock, config: Config, *,
    session: GuardianSession, patient_id: int, count: int, ml: int | None = None,
) -> Submission:
    patient = db.get(Patient, patient_id)  # 첫 쿼리: 쓰기 잠금
    if patient is None or patient.guardian_phone != session.phone:
        raise Forbidden("본인 아기만 제출할 수 있습니다")
    if patient.status == PatientStatus.DISCHARGED:
        raise Conflict("퇴원한 아기입니다")
    if not 1 <= count <= 10:
        raise BadRequest("팩 수는 1~10 사이여야 합니다")
    stock.validate_ml(ml)
    waiting = db.scalar(
        select(func.count()).select_from(Submission).where(
            Submission.patient_id == patient_id, Submission.status == SubmissionStatus.PENDING
        )
    )
    if waiting >= config.pending_limit:
        raise Conflict("확인을 기다리는 제출이 많습니다. 간호사에게 말씀해 주세요")
    sub = Submission(
        patient_id=patient_id, station_id=session.station_id, count=count, ml=ml,
        submitted_at=clock.now(), status=SubmissionStatus.PENDING,
    )
    db.add(sub)
    db.flush()
    return sub


@dataclass
class PendingView:
    id: int
    patient_id: int
    baby_id: str
    balance: int
    station_name: str
    submitted_at: datetime
    count: int
    ml: int | None
    discharged: bool


def pending(db: Session) -> list[PendingView]:
    rows = db.execute(
        select(Submission, Patient, Station.name)
        .join(Patient, Patient.id == Submission.patient_id)
        .outerjoin(Station, Station.id == Submission.station_id)
        .where(Submission.status == SubmissionStatus.PENDING)
        .order_by(Submission.submitted_at, Submission.id)
    ).all()
    return [
        PendingView(
            id=s.id, patient_id=p.id, baby_id=p.reg_no, balance=stock.balance(db, p.id),
            station_name=station_name or GENERAL_DEVICE_NAME, submitted_at=s.submitted_at, count=s.count,
            ml=s.ml, discharged=p.status == PatientStatus.DISCHARGED,
        )
        for s, p, station_name in rows
    ]


def _load_pending(db: Session, submission_id: int) -> Submission:
    sub = db.get(Submission, submission_id)  # 첫 쿼리: 쓰기 잠금
    if sub is None:
        raise NotFound("제출을 찾을 수 없습니다")
    if sub.status != SubmissionStatus.PENDING:
        raise Conflict("이미 처리된 제출입니다")
    return sub


def confirm(
    db: Session, clock: Clock, *, submission_id: int, count: int,
    ml: int | None = None, ml_given: bool = False, staff: Staff,
) -> tuple[Submission, StockRecord]:
    sub = _load_pending(db, submission_id)
    patient = db.get(Patient, sub.patient_id)
    if patient.status == PatientStatus.DISCHARGED:
        raise Conflict("퇴원한 아기입니다. 반려만 할 수 있습니다")
    if not 1 <= count <= 100:
        raise BadRequest("팩 수는 1~100 사이여야 합니다")
    ml = stock.validate_ml(ml if ml_given else sub.ml)
    station = db.get(Station, sub.station_id) if sub.station_id is not None else None
    source = station.name if station is not None else GENERAL_DEVICE_NAME
    record = stock.add_record(
        db, clock, patient_id=sub.patient_id, kind=RecordKind.INTAKE, count=count,
        ml=ml, staff=staff, memo=f"{source} 제출분(제출 {sub.count}팩)",
    )
    sub.status = SubmissionStatus.CONFIRMED
    sub.confirmed_count = count
    sub.confirmed_ml = ml
    sub.handled_by = staff.id
    sub.handled_at = clock.now()
    sub.intake_record_id = record.id
    db.flush()
    return sub, record


def reject(
    db: Session, clock: Clock, *, submission_id: int, reason: RejectReason,
    memo: str | None, staff: Staff,
) -> Submission:
    sub = _load_pending(db, submission_id)
    memo = (memo or "").strip() or None
    if reason == RejectReason.OTHER and memo is None:
        raise BadRequest("기타 사유를 입력해 주세요")
    if memo is not None and len(memo) > 100:
        raise BadRequest("메모는 100자 이하로 입력해 주세요")
    sub.status = SubmissionStatus.REJECTED
    sub.reject_reason = reason
    sub.memo = memo
    sub.handled_by = staff.id
    sub.handled_at = clock.now()
    db.flush()
    return sub
