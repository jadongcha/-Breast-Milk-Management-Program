from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy.orm import Session

from .. import staff as staff_service
from ..clock import Clock
from ..config import Config
from ..errors import Forbidden
from ..models import Role, Staff


def get_db(request: Request) -> Iterator[Session]:
    db = request.app.state.session_factory()
    try:
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


def get_clock(request: Request) -> Clock:
    return request.app.state.clock


def get_config(request: Request) -> Config:
    return request.app.state.config


# scope="function": 종료 코드(commit)가 응답 전송 전에 실행되어
# 다음 요청이 변경을 확실히 보게 한다.
DbDep = Annotated[Session, Depends(get_db, scope="function")]


def bearer_token(request: Request) -> str | None:
    scheme, _, value = request.headers.get("Authorization", "").partition(" ")
    if scheme.lower() != "bearer" or not value.strip():
        return None
    return value.strip()


def current_staff_any(request: Request, db: DbDep) -> Staff:
    config: Config = request.app.state.config
    return staff_service.authenticate(
        db, request.app.state.clock, bearer_token(request), config.staff_idle_minutes
    )


def current_staff(staff: Annotated[Staff, Depends(current_staff_any)]) -> Staff:
    if staff.must_change_password:
        raise Forbidden("비밀번호를 먼저 변경해야 합니다")
    return staff


def require_admin(staff: Annotated[Staff, Depends(current_staff)]) -> Staff:
    if staff.role != Role.ADMIN:
        raise Forbidden("관리자만 사용할 수 있습니다")
    return staff
