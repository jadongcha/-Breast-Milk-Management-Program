from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends, Request
from sqlalchemy.orm import Session

from .. import guardians as guardian_service
from .. import staff as staff_service
from .. import stations as station_service
from ..clock import Clock
from ..config import Config
from ..errors import DomainError, Forbidden
from ..models import GuardianSession, Role, Staff, Station


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


def current_staff_no_refresh(request: Request, db: DbDep) -> Staff:
    """로그인 유지 확인용: 마지막 활동 시각을 갱신하지 않는다."""
    config: Config = request.app.state.config
    return staff_service.authenticate(
        db,
        request.app.state.clock,
        bearer_token(request),
        config.staff_idle_minutes,
        refresh=False,
    )


def _require_password_changed(staff: Staff) -> Staff:
    if staff.must_change_password:
        raise Forbidden("비밀번호를 먼저 변경해야 합니다")
    return staff


def current_staff(staff: Annotated[Staff, Depends(current_staff_any)]) -> Staff:
    return _require_password_changed(staff)


def current_staff_check(staff: Annotated[Staff, Depends(current_staff_no_refresh)]) -> Staff:
    return _require_password_changed(staff)


def require_admin(staff: Annotated[Staff, Depends(current_staff)]) -> Staff:
    if staff.role != Role.ADMIN:
        raise Forbidden("관리자만 사용할 수 있습니다")
    return staff


def current_station(request: Request, db: DbDep) -> Station:
    return station_service.authenticate_station(db, request.headers.get("X-Station-Token"))


StationDep = Annotated[Station, Depends(current_station)]


def optional_station(request: Request, db: DbDep) -> Station | None:
    """헤더가 없으면 일반 병원 기기(None). 있으면 유효해야 한다(무효면 401 station_invalid)."""
    if request.headers.get("X-Station-Token") is None:
        return None
    return station_service.authenticate_station(db, request.headers.get("X-Station-Token"))


OptionalStationDep = Annotated[Station | None, Depends(optional_station)]


def current_guardian(request: Request, db: DbDep, station: OptionalStationDep) -> GuardianSession:
    # 직원 세션 표는 보지 않는다. 만료된 세션 삭제가 저장되도록 실패 때도 commit한다.
    try:
        return guardian_service.authenticate(
            db, request.app.state.clock, request.app.state.config,
            station=station, raw_token=bearer_token(request),
        )
    except DomainError:
        db.commit()
        raise


GuardianDep = Annotated[GuardianSession, Depends(current_guardian)]
