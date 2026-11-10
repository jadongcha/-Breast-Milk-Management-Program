from typing import Annotated

from fastapi import APIRouter, Depends, Request

from .. import staff as staff_service
from ..models import Staff
from ..schemas import (
    StaffCreateIn,
    StaffCreateOut,
    StaffOut,
    StaffPatchIn,
    TempPasswordOut,
)
from .deps import DbDep, require_admin

router = APIRouter(prefix="/staff", tags=["staff"])

AdminDep = Annotated[Staff, Depends(require_admin)]


@router.get("", response_model=list[StaffOut])
def list_staff(db: DbDep, _: AdminDep):
    return staff_service.list_staff(db)


@router.post("", response_model=StaffCreateOut, status_code=201)
def create_staff(body: StaffCreateIn, request: Request, db: DbDep, _: AdminDep):
    staff, temp = staff_service.create_staff(
        db, request.app.state.clock, username=body.username, name=body.name, role=body.role
    )
    return StaffCreateOut(staff=StaffOut.model_validate(staff), temp_password=temp)


@router.patch("/{staff_id}", response_model=StaffOut)
def patch_staff(staff_id: int, body: StaffPatchIn, db: DbDep, actor: AdminDep):
    return staff_service.set_active(db, actor, staff_id, body.active)


@router.post("/{staff_id}/reset-password", response_model=TempPasswordOut)
def reset_password(staff_id: int, db: DbDep, _: AdminDep):
    return TempPasswordOut(temp_password=staff_service.reset_password(db, staff_id))
