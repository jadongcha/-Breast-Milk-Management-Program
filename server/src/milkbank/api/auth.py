from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response

from .. import staff as staff_service
from ..errors import DomainError
from ..models import Staff
from ..schemas import LoginIn, LoginOut, PasswordChangeIn, StaffOut
from .deps import DbDep, bearer_token, current_staff_any, current_staff_check

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=LoginOut)
def login(body: LoginIn, request: Request, db: DbDep):
    config = request.app.state.config
    try:
        staff, token = staff_service.login(
            db,
            request.app.state.clock,
            body.username,
            body.password,
            lock_threshold=config.login_lock_threshold,
            lock_minutes=config.login_lock_minutes,
        )
    except DomainError:
        # 실패 횟수 기록이 오류 응답에도 남도록 저장한 뒤 다시 던진다.
        db.commit()
        raise
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
def check(_: Annotated[Staff, Depends(current_staff_check)]):
    # 마지막 활동 시각을 갱신하지 않는다(주기적 확인이 자동 로그아웃을 막지 않도록).
    return Response(status_code=204)
