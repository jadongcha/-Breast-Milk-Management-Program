from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response

from .. import reports
from .. import settings as settings_service
from ..clock import Clock
from ..errors import BadRequest
from ..models import Staff
from ..schemas import (
    DailyOut,
    OverviewOut,
    SeriesPointOut,
    SettingsOut,
    SettingsPatch,
)
from .deps import DbDep, get_clock, require_admin

router = APIRouter(prefix="/admin", tags=["admin"])

AdminDep = Annotated[Staff, Depends(require_admin)]


@router.get("/settings", response_model=SettingsOut)
def get_settings(db: DbDep, _: AdminDep):
    return settings_service.get(db)


@router.patch("/settings", response_model=SettingsOut)
def patch_settings(body: SettingsPatch, db: DbDep, _: AdminDep):
    changes = body.model_dump(exclude_none=True)
    return settings_service.update(db, changes)


@router.get("/overview", response_model=OverviewOut)
def get_overview(db: DbDep, _: AdminDep, clock: Annotated[Clock, Depends(get_clock)]):
    return reports.overview(db, clock, settings_service.get(db))


def _sum_ml(values: list[int | None]) -> int | None:
    present = [v for v in values if v is not None]
    return sum(present) if present else None


@router.get("/daily", response_model=DailyOut)
def get_daily(
    db: DbDep,
    _: AdminDep,
    clock: Annotated[Clock, Depends(get_clock)],
    day: Annotated[date | None, Query(alias="date")] = None,
):
    day = day or reports.kst_today(clock)
    rows = reports.daily_rows(db, day, day)
    total = {
        "in_packs": sum(r.in_packs for r in rows),
        "in_ml": _sum_ml([r.in_ml for r in rows]),
        "use_packs": sum(r.use_packs for r in rows),
        "use_ml": _sum_ml([r.use_ml for r in rows]),
        "discard_packs": sum(r.discard_packs for r in rows),
        "discard_ml": _sum_ml([r.discard_ml for r in rows]),
        "return_packs": sum(r.return_packs for r in rows),
    }
    return {
        "date": day,
        "rows": [{k: v for k, v in vars(r).items() if k != "day"} for r in rows],
        "total": total,
    }


@router.get("/series", response_model=list[SeriesPointOut])
def get_series(
    db: DbDep,
    _: AdminDep,
    clock: Annotated[Clock, Depends(get_clock)],
    days: int,
    patient_id: int | None = None,
):
    if days not in (7, 30):
        raise BadRequest("기간은 7일 또는 30일만 고를 수 있습니다")
    return reports.series(db, reports.kst_today(clock), days, patient_id)


@router.get("/export.csv")
def export_csv(
    db: DbDep,
    _: AdminDep,
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
):
    if start > end:
        raise BadRequest("시작일이 종료일보다 늦습니다")
    if (end - start).days + 1 > 92:
        raise BadRequest("기간은 92일 이하로 골라 주세요")
    return Response(
        content=reports.csv_bytes(reports.daily_rows(db, start, end)),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="milkbank-{start}-{end}.csv"'
        },
    )
