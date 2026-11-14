"""팩 서비스: 재고는 팩 한 개 한 개이고, 기록은 팩을 들이거나 내보낸다.

모든 트랜잭션은 BEGIN IMMEDIATE로 시작하므로, 호출하는 쪽의 첫 쿼리가 쓰기 잠금을 잡는다.
따라서 "재고 팩 고르기 -> 내보내기"는 같은 트랜잭션 안에서 원자적으로 이루어진다.
"""
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import delete, func, select, update
from sqlalchemy.orm import Session

from .errors import BadRequest, Conflict
from .models import Pack, StockRecord


def validate_pack_ml(ml: int | None) -> int | None:
    if ml is not None and not 1 <= ml <= 1000:
        raise BadRequest("팩 용량은 1~1000mL 사이여야 합니다")
    return ml


@dataclass(frozen=True)
class Summary:
    ml: int  # 재고 팩 중 용량을 아는 팩의 합
    unknown: int  # 재고 팩 중 용량을 모르는 팩 수


def summary(db: Session, patient_id: int) -> Summary:
    ml, unknown = db.execute(
        select(
            func.coalesce(func.sum(Pack.ml), 0),
            func.count(Pack.id) - func.count(Pack.ml),
        ).where(Pack.patient_id == patient_id, Pack.out_record_id.is_(None))
    ).one()
    return Summary(ml=ml, unknown=unknown)


@dataclass
class PackView:
    id: int
    ml: int | None
    created_at: datetime


def _stock_query(patient_id: int):
    return (
        select(Pack)
        .where(Pack.patient_id == patient_id, Pack.out_record_id.is_(None))
        .order_by(Pack.created_at, Pack.id)
    )


def in_stock(db: Session, patient_id: int) -> list[PackView]:
    """재고 팩. 나갈 순서(입고 시각이 이른 순, 같으면 팩 번호 순)대로."""
    return [
        PackView(id=p.id, ml=p.ml, created_at=p.created_at)
        for p in db.scalars(_stock_query(patient_id))
    ]


def _fill_totals(record: StockRecord, mls: list[int | None]) -> None:
    known = [m for m in mls if m is not None]
    record.ml = sum(known) if known else None
    record.ml_unknown = len(mls) - len(known)


def validate_pack_mls(pack_mls: list[int | None], count: int) -> None:
    if len(pack_mls) != count:
        raise BadRequest("팩마다 용량 칸 수가 맞지 않습니다")
    for ml in pack_mls:
        validate_pack_ml(ml)


def create(db: Session, record: StockRecord, pack_mls: list[int | None]) -> None:
    validate_pack_mls(pack_mls, record.count)
    for ml in pack_mls:
        db.add(
            Pack(
                patient_id=record.patient_id,
                ml=ml,
                in_record_id=record.id,
                out_record_id=None,
                created_at=record.created_at,
            )
        )
    _fill_totals(record, pack_mls)
    db.flush()


def take(db: Session, record: StockRecord, pack_ids: list[int] | None) -> None:
    """record.count개의 재고 팩을 내보낸다. pack_ids가 없으면 먼저 들어온 순."""
    if pack_ids is None:
        chosen = list(db.scalars(_stock_query(record.patient_id).limit(record.count)))
    else:
        if len(pack_ids) != record.count or len(set(pack_ids)) != len(pack_ids):
            raise Conflict("고른 팩을 다시 확인해 주세요")
        chosen = list(
            db.scalars(
                _stock_query(record.patient_id).where(Pack.id.in_(pack_ids))
            )
        )
    if len(chosen) != record.count:
        raise Conflict("고른 팩을 다시 확인해 주세요" if pack_ids is not None else "재고가 부족합니다")
    db.execute(
        update(Pack).where(Pack.id.in_([p.id for p in chosen])).values(out_record_id=record.id)
    )
    _fill_totals(record, [p.ml for p in chosen])
    db.flush()


def release(db: Session, out_record_id: int) -> None:
    """사용·폐기·반환을 취소할 때: 그 기록으로 나간 팩을 재고로 되돌린다."""
    db.execute(update(Pack).where(Pack.out_record_id == out_record_id).values(out_record_id=None))
    db.flush()


def remove_intake(db: Session, in_record_id: int) -> None:
    """입고를 취소할 때: 그 입고의 팩을 없앤다. 하나라도 나갔으면 취소할 수 없다."""
    used = db.scalar(
        select(Pack.id)
        .where(Pack.in_record_id == in_record_id, Pack.out_record_id.is_not(None))
        .limit(1)
    )
    if used is not None:
        raise Conflict("이 입고의 팩이 이미 사용되었습니다. 사용 기록을 먼저 취소해 주세요")
    db.execute(delete(Pack).where(Pack.in_record_id == in_record_id))
    db.flush()
