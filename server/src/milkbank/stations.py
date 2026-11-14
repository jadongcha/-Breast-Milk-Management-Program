"""제출처(제출용 태블릿) 서비스: 등록, 목록, 사용 중지, 토큰 인증."""
from sqlalchemy import case, delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .clock import Clock
from .errors import BadRequest, Conflict, NotFound, Unauthorized
from .models import GuardianSession, Staff, Station
from .security import hash_token, new_token
from .submissions import GENERAL_DEVICE_NAME

MAX_NAME_LEN = 20
_DUP = "이미 있는 제출처 이름입니다"


def create_station(
    db: Session, clock: Clock, *, name: str, actor: Staff
) -> tuple[Station, str]:
    name = name.strip()
    if not 1 <= len(name) <= MAX_NAME_LEN:
        raise BadRequest("제출처 이름은 1~20자로 입력해 주세요")
    if name == GENERAL_DEVICE_NAME:
        raise BadRequest("‘병원 기기’는 제출처 이름으로 쓸 수 없습니다")
    if db.scalar(select(Station.id).where(Station.name == name, Station.active)) is not None:
        raise Conflict(_DUP)
    raw, hashed = new_token()
    station = Station(
        name=name, token_hash=hashed, active=True, created_at=clock.now(), created_by=actor.id
    )
    db.add(station)
    try:
        db.flush()
    except IntegrityError:
        raise Conflict(_DUP) from None
    return station, raw


def list_stations(db: Session) -> list[Station]:
    return list(
        db.scalars(
            select(Station).order_by(case((Station.active, 0), else_=1), Station.name, Station.id)
        )
    )


def deactivate_station(db: Session, station_id: int) -> Station:
    station = db.get(Station, station_id)
    if station is None:
        raise NotFound("제출처를 찾을 수 없습니다")
    if not station.active:
        return station
    station.active = False
    db.execute(delete(GuardianSession).where(GuardianSession.station_id == station.id))
    return station


def authenticate_station(db: Session, raw_token: str | None) -> Station:
    station = None
    if raw_token:
        station = db.scalar(select(Station).where(Station.token_hash == hash_token(raw_token)))
    if station is None or not station.active:
        raise Unauthorized("등록된 제출처 기기가 아닙니다", code="station_invalid")
    return station
