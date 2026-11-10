from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response

from .. import staff as staff_service
from ..models import Staff
from ..schemas import LoginIn, LoginOut, PasswordChangeIn, StaffOut
from .deps import DbDep, bearer_token, current_staff, current_staff_any

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=LoginOut)
def login(body: LoginIn, request: Request, db: DbDep):
    staff, token = staff_service.login(
        db, request.app.state.clock, body.username, body.password
    )
    return LoginOut(
        token=token,
        must_change_password=staff.must_change_password,
        staff=StaffOut.model_validate(staff),
    )


@router.post("/logout", status_code=204)
def logout(
    request: Request,
    db: DbDep,
    _: Annotated[Staff, Depends(current_staff_any)],
):
    staff_service.logout(db, bearer_token(request))
    return Response(status_code=204)


@router.post("/password", status_code=204)
def change_password(
    body: PasswordChangeIn,
    db: DbDep,
    staff: Annotated[Staff, Depends(current_staff_any)],
):
    staff_service.change_password(db, staff, body.current_password, body.new_password)
    return Response(status_code=204)


@router.get("/me", response_model=StaffOut)
def me(staff: Annotated[Staff, Depends(current_staff_any)]):
    return staff


@router.get("/check", status_code=204)
def check(_: Annotated[Staff, Depends(current_staff)]):
    return Response(status_code=204)
