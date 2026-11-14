from typing import Annotated

from fastapi import APIRouter, Depends

from .. import reports
from .. import settings as settings_service
from ..clock import Clock
from ..models import Staff
from ..schemas import OverviewOut, SettingsOut, SettingsPatch
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
