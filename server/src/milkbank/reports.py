"""관리자 현황·판정 입력 계산."""
import csv
import io
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .clock import KST, Clock
from .judge import Judgement, judge
from . import stock
from .models import (
    Patient,
    PatientStatus,
    RecordKind,
    StockRecord,
    Submission,
    SubmissionStatus,
)
from .patients import bed_sort_key
from .settings import Settings


def kst_today(clock: Clock) -> date:
    return clock.now().astimezone(KST).date()


def _kst_day_start(day: date) -> datetime:
    return datetime(day.year, day.month, day.day, tzinfo=KST)


def judge_inputs(
    db: Session, patient_id: int, today: date, avg_days: int
) -> tuple[date | None, int]:
    """(취소 제외 첫 사용 기록의 KST 날짜, 오늘을 뺀 최근 avg_days일 사용 팩 합)."""
    base = (
        select(StockRecord)
        .where(
            StockRecord.patient_id == patient_id,
            StockRecord.kind == RecordKind.USE,
            StockRecord.id.not_in(stock._canceled_ids()),
        )
    )
    first = db.scalar(base.order_by(StockRecord.created_at, StockRecord.id).limit(1))
    first_day = first.created_at.astimezone(KST).date() if first else None
    # 최근 avg_days일: [오늘-avg_days, 오늘) — 오늘은 제외
    start = _kst_day_start(today - timedelta(days=avg_days))
    end = _kst_day_start(today)
    packs = db.scalar(
        select(func.coalesce(func.sum(StockRecord.count), 0)).where(
            StockRecord.patient_id == patient_id,
            StockRecord.kind == RecordKind.USE,
            StockRecord.id.not_in(stock._canceled_ids()),
            StockRecord.created_at >= start,
            StockRecord.created_at < end,
        )
    )
    return first_day, packs


def judge_patient(db: Session, patient: Patient, today: date, s: Settings) -> Judgement:
    first_day, recent = judge_inputs(db, patient.id, today, s.avg_days)
    return judge(
        balance=stock.balance(db, patient.id),
        first_use_day=first_day,
        recent_use_packs=recent,
        today=today,
        avg_days=s.avg_days,
        days_threshold=s.days_threshold if patient.days_threshold is None else patient.days_threshold,
        packs_threshold=s.packs_threshold if patient.packs_threshold is None else patient.packs_threshold,
    )


def _today_sum(db: Session, today: date, kind: RecordKind) -> tuple[int, int | None]:
    """오늘(KST) 해당 종류 기록의 (팩 합, mL 합 — 입력된 값이 없으면 None)."""
    packs, ml = db.execute(
        select(func.coalesce(func.sum(StockRecord.count), 0), func.sum(StockRecord.ml)).where(
            StockRecord.kind == kind,
            StockRecord.id.not_in(stock._canceled_ids()),
            StockRecord.created_at >= _kst_day_start(today),
            StockRecord.created_at < _kst_day_start(today + timedelta(days=1)),
        )
    ).one()
    return packs, ml


def overview(db: Session, clock: Clock, s: Settings) -> dict:
    today = kst_today(clock)
    patients = sorted(
        db.scalars(select(Patient).where(Patient.status == PatientStatus.ADMITTED)),
        key=lambda p: bed_sort_key(p.bed),
    )
    pending = dict(
        db.execute(
            select(Submission.patient_id, func.sum(Submission.count))
            .where(Submission.status == SubmissionStatus.PENDING)
            .group_by(Submission.patient_id)
        ).all()
    )
    rows = []
    for p in patients:
        j = judge_patient(db, p, today, s)
        rows.append(
            {
                "patient_id": p.id,
                "baby_id": p.reg_no,
                "bed": p.bed,
                "balance": stock.balance(db, p.id),
                "daily_avg": j.daily_avg,
                "days_left": j.days_left,
                "status": j.status,
                "pending_packs": pending.get(p.id, 0),
            }
        )
    in_packs, in_ml = _today_sum(db, today, RecordKind.INTAKE)
    use_packs, use_ml = _today_sum(db, today, RecordKind.USE)
    pending_count = db.scalar(
        select(func.count()).select_from(Submission).where(
            Submission.status == SubmissionStatus.PENDING
        )
    )
    return {
        "admitted": len(rows),
        "low": sum(1 for r in rows if r["status"] != "enough"),
        "today_in_packs": in_packs,
        "today_in_ml": in_ml,
        "today_use_packs": use_packs,
        "today_use_ml": use_ml,
        "pending_submissions": pending_count,
        "rows": rows,
    }


@dataclass
class DayRow:
    day: date
    patient_id: int
    baby_id: str
    bed: str
    in_packs: int
    in_ml: int | None
    use_packs: int
    use_ml: int | None
    discard_packs: int
    discard_ml: int | None
    return_packs: int


@dataclass
class SeriesPoint:
    day: date
    in_packs: int
    use_packs: int
    discard_packs: int
    use_ml: int | None


def _add_ml(total: int | None, ml: int | None) -> int | None:
    if ml is None:
        return total
    return ml if total is None else total + ml


def _live_records(db: Session, start: date, end: date, patient_id: int | None = None):
    """start~end(포함, KST) 기간의 취소 기록과 취소된 기록을 뺀 (기록, 환자) 목록."""
    q = (
        select(StockRecord, Patient)
        .join(Patient, Patient.id == StockRecord.patient_id)
        .where(
            StockRecord.kind != RecordKind.CANCEL,
            StockRecord.id.not_in(stock._canceled_ids()),
            StockRecord.created_at >= _kst_day_start(start),
            StockRecord.created_at < _kst_day_start(end + timedelta(days=1)),
        )
        .order_by(StockRecord.created_at, StockRecord.id)
    )
    if patient_id is not None:
        q = q.where(StockRecord.patient_id == patient_id)
    return db.execute(q).all()


def daily_rows(db: Session, start: date, end: date) -> list[DayRow]:
    rows: dict[tuple[date, int], DayRow] = {}
    for rec, p in _live_records(db, start, end):
        day = rec.created_at.astimezone(KST).date()
        row = rows.get((day, p.id))
        if row is None:
            row = rows[(day, p.id)] = DayRow(
                day, p.id, p.reg_no, p.bed, 0, None, 0, None, 0, None, 0
            )
        if rec.kind == RecordKind.INTAKE:
            row.in_packs += rec.count
            row.in_ml = _add_ml(row.in_ml, rec.ml)
        elif rec.kind == RecordKind.USE:
            row.use_packs += rec.count
            row.use_ml = _add_ml(row.use_ml, rec.ml)
        elif rec.kind == RecordKind.DISCARD:
            row.discard_packs += rec.count
            row.discard_ml = _add_ml(row.discard_ml, rec.ml)
        elif rec.kind == RecordKind.RETURN:
            row.return_packs += rec.count
    return sorted(rows.values(), key=lambda r: (r.day, bed_sort_key(r.bed), r.patient_id))


def series(db: Session, today: date, days: int, patient_id: int | None) -> list[SeriesPoint]:
    first = today - timedelta(days=days - 1)
    points = {
        first + timedelta(days=i): SeriesPoint(first + timedelta(days=i), 0, 0, 0, None)
        for i in range(days)
    }
    for rec, _ in _live_records(db, first, today, patient_id):
        pt = points[rec.created_at.astimezone(KST).date()]
        if rec.kind == RecordKind.INTAKE:
            pt.in_packs += rec.count
        elif rec.kind == RecordKind.USE:
            pt.use_packs += rec.count
            pt.use_ml = _add_ml(pt.use_ml, rec.ml)
        elif rec.kind == RecordKind.DISCARD:
            pt.discard_packs += rec.count
    return list(points.values())


CSV_HEADER = ["날짜", "아기 ID", "병상", "입고 팩", "입고 mL", "사용 팩", "사용 mL",
              "폐기 팩", "폐기 mL", "퇴원 반환 팩"]


def _blank(v: int | None) -> int | str:
    return "" if v is None else v


def csv_bytes(rows: list[DayRow]) -> bytes:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    w.writerow(CSV_HEADER)
    for r in rows:
        w.writerow(
            [r.day.isoformat(), r.baby_id, r.bed, r.in_packs, _blank(r.in_ml),
             r.use_packs, _blank(r.use_ml), r.discard_packs, _blank(r.discard_ml),
             r.return_packs]
        )
    return b"\xef\xbb\xbf" + buf.getvalue().encode("utf-8")
