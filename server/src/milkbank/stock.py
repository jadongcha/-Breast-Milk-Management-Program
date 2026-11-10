"""재고 서비스: 잔량, 기록 추가, 취소, 최근 기록.

기록은 추가만 한다(UPDATE/DELETE 없음). 바로잡을 때는 CANCEL 기록을 추가한다.
모든 트랜잭션은 BEGIN IMMEDIATE로 시작하므로, 각 함수의 첫 쿼리가 쓰기 잠금을 잡는다.
따라서 "잔량 확인 -> 기록 추가"는 같은 세션(트랜잭션) 안에서 원자적으로 이루어진다.
"""
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .clock import Clock
from .errors import BadRequest, Conflict, Forbidden, NotFound
from .models import (
    DiscardReason,
    Patient,
    PatientStatus,
    RecordKind,
    Role,
    Staff,
    StockRecord,
)

_OUTFLOW = (RecordKind.USE, RecordKind.DISCARD, RecordKind.RETURN)


def _canceled_ids():
    return select(StockRecord.target_id).where(
        StockRecord.kind == RecordKind.CANCEL, StockRecord.target_id.is_not(None)
    )


def balance(db: Session, patient_id: int) -> int:
    base = select(func.coalesce(func.sum(StockRecord.count), 0)).where(
        StockRecord.patient_id == patient_id,
        StockRecord.id.not_in(_canceled_ids()),
    )
    intake = db.scalar(base.where(StockRecord.kind == RecordKind.INTAKE))
    outflow = db.scalar(base.where(StockRecord.kind.in_(_OUTFLOW)))
    return intake - outflow


def add_record(
    db: Session,
    clock: Clock,
    *,
    patient_id: int,
    kind: RecordKind,
    count: int,
    staff: Staff,
    discard_reason: DiscardReason | None = None,
    memo: str | None = None,
) -> StockRecord:
    # 첫 쿼리가 쓰기 잠금을 잡는다. 이후 잔량 확인과 삽입이 한 트랜잭션에서 이뤄진다.
    patient = db.get(Patient, patient_id)
    if patient is None:
        raise NotFound("환자를 찾을 수 없습니다")
    if patient.status == PatientStatus.DISCHARGED:
        raise Conflict("퇴원한 환자입니다")
    if kind == RecordKind.CANCEL:
        raise BadRequest("잘못된 기록 종류입니다")
    if not 1 <= count <= 100:
        raise BadRequest("팩 수는 1~100 사이여야 합니다")
    if kind == RecordKind.DISCARD and discard_reason is None:
        raise BadRequest("폐기 사유를 선택해 주세요")
    if kind != RecordKind.DISCARD and discard_reason is not None:
        raise BadRequest("폐기일 때만 사유를 넣을 수 있습니다")
    if kind in _OUTFLOW:
        current = balance(db, patient_id)
        if count > current:
            raise Conflict(f"재고가 부족합니다 (현재 {current}팩)")
    memo = (memo or "").strip() or None
    record = StockRecord(
        patient_id=patient_id,
        kind=kind,
        count=count,
        discard_reason=discard_reason,
        target_id=None,
        memo=memo,
        staff_id=staff.id,
        created_at=clock.now(),
    )
    db.add(record)
    db.flush()
    return record


def cancel_record(
    db: Session,
    clock: Clock,
    *,
    record_id: int,
    staff: Staff,
    cancel_window_minutes: int,
) -> StockRecord:
    # 첫 쿼리가 쓰기 잠금을 잡으므로, 동시에 같은 기록을 취소해도 한쪽만 통과한다.
    target = db.get(StockRecord, record_id)
    if target is None:
        raise NotFound("기록을 찾을 수 없습니다")
    if target.kind == RecordKind.CANCEL:
        raise Conflict("취소 기록은 취소할 수 없습니다")
    already = db.scalar(
        select(StockRecord.id).where(
            StockRecord.kind == RecordKind.CANCEL, StockRecord.target_id == target.id
        )
    )
    if already is not None:
        raise Conflict("이미 취소된 기록입니다")
    patient = db.get(Patient, target.patient_id)
    if patient.status == PatientStatus.DISCHARGED:
        raise Conflict("퇴원한 환자입니다")
    now = clock.now()
    if staff.role == Role.NURSE:
        if target.staff_id != staff.id:
            raise Forbidden("본인이 입력한 기록만 취소할 수 있습니다")
        if now - target.created_at > timedelta(minutes=cancel_window_minutes):
            raise Forbidden("입력 후 10분이 지나 관리자만 취소할 수 있습니다")
    if target.kind == RecordKind.INTAKE and balance(db, target.patient_id) - target.count < 0:
        raise Conflict("취소하면 재고가 음수가 됩니다")
    record = StockRecord(
        patient_id=target.patient_id,
        kind=RecordKind.CANCEL,
        count=target.count,
        discard_reason=None,
        target_id=target.id,
        memo=None,
        staff_id=staff.id,
        created_at=now,
    )
    db.add(record)
    db.flush()
    return record


@dataclass
class RecordView:
    id: int
    kind: RecordKind
    count: int
    discard_reason: DiscardReason | None
    target_id: int | None
    memo: str | None
    staff_name: str
    created_at: datetime
    canceled: bool


def _views(db: Session, patient_id: int, rows) -> list[RecordView]:
    canceled = set(
        db.scalars(
            select(StockRecord.target_id).where(
                StockRecord.patient_id == patient_id,
                StockRecord.kind == RecordKind.CANCEL,
                StockRecord.target_id.is_not(None),
            )
        )
    )
    return [
        RecordView(
            id=r.id,
            kind=r.kind,
            count=r.count,
            discard_reason=r.discard_reason,
            target_id=r.target_id,
            memo=r.memo,
            staff_name=name,
            created_at=r.created_at,
            canceled=r.id in canceled,
        )
        for r, name in rows
    ]


def recent_records(db: Session, patient_id: int, limit: int = 50) -> list[RecordView]:
    rows = db.execute(
        select(StockRecord, Staff.name)
        .join(Staff, Staff.id == StockRecord.staff_id)
        .where(StockRecord.patient_id == patient_id)
        .order_by(StockRecord.created_at.desc(), StockRecord.id.desc())
        .limit(limit)
    ).all()
    return _views(db, patient_id, rows)


def record_view(db: Session, record: StockRecord) -> RecordView:
    """기록 한 건을 recent_records와 같은 형태(직원 이름, 취소 여부 포함)로 돌려준다."""
    rows = db.execute(
        select(StockRecord, Staff.name)
        .join(Staff, Staff.id == StockRecord.staff_id)
        .where(StockRecord.id == record.id)
    ).all()
    return _views(db, record.patient_id, rows)[0]
