"""보호자 서비스: 제출처 기기 또는 일반 병원 기기에서의 로그인, 세션 인증, 아기 목록.

모든 트랜잭션은 BEGIN IMMEDIATE로 시작하므로 각 함수의 첫 쿼리가 쓰기 잠금을 잡는다.
"""
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from . import lockout
from .clock import Clock
from .config import Config
from .errors import BadRequest, Unauthorized
from .models import (
    GuardianSession,
    Patient,
    PatientStatus,
    Station,
    Submission,
    SubmissionStatus,
)
from .patients import normalize_phone
from .security import hash_token, new_token
from .stock import balance

_BAD_LOGIN = "전화번호 또는 아기 ID가 올바르지 않습니다"
_NEED_LOGIN = "로그인이 필요합니다"
_STATION_LOCKED = "잠시 뒤에 다시 시도해 주세요"


@dataclass
class BabyView:
    patient_id: int
    baby_id: str
    balance: int
    pending_count: int


def _same_station(station_id: int | None):
    # 일반 기기 세션은 station_id가 NULL이라 `=`가 아니라 IS NULL로 비교해야 한다.
    if station_id is None:
        return GuardianSession.station_id.is_(None)
    return GuardianSession.station_id == station_id


def guardian_babies(db: Session, phone: str) -> list[BabyView]:
    patients = db.scalars(
        select(Patient)
        .where(Patient.guardian_phone == phone, Patient.status == PatientStatus.ADMITTED)
        .order_by(Patient.reg_no, Patient.id)
    ).all()
    views = []
    for p in patients:
        pending = db.scalar(
            select(func.coalesce(func.sum(Submission.count), 0)).where(
                Submission.patient_id == p.id, Submission.status == SubmissionStatus.PENDING
            )
        )
        views.append(BabyView(p.id, p.reg_no, balance(db, p.id), pending))
    return views


def login(
    db: Session, clock: Clock, config: Config, *,
    station: Station | None, client_ip: str | None, phone: str, baby_id: str,
) -> tuple[str, list[BabyView]]:
    now = clock.now()
    # 제출처 토큰이 없으면(일반 병원 기기) 접속 IP별로 잠근다.
    station_key = (
        f"station:{station.id}" if station is not None else f"guestip:{client_ip or 'unknown'}"
    )
    station_id = station.id if station is not None else None
    lockout.ensure_not_locked(db, station_key, now, _STATION_LOCKED)

    def station_failure() -> None:
        lockout.record_failure(
            db, station_key, now,
            threshold=config.station_lock_threshold,
            window_minutes=config.station_lock_window_minutes,
            lock_minutes=config.station_lock_minutes,
        )

    try:
        number = normalize_phone(phone)
    except BadRequest:
        station_failure()
        raise
    # 전화번호 원문이 로그인 기록 표에 남지 않도록 해시를 키로 쓴다.
    phone_key = f"guardian:{hash_token(number)}"
    lockout.ensure_not_locked(
        db, phone_key, now,
        f"로그인을 {config.login_lock_threshold}번 실패해 {config.login_lock_minutes}분 동안 "
        "잠겼습니다. 잠시 후 다시 시도해 주세요",
    )
    wanted = baby_id.strip().casefold()
    admitted = db.scalars(
        select(Patient).where(
            Patient.guardian_phone == number, Patient.status == PatientStatus.ADMITTED
        )
    ).all()
    if not any(p.reg_no.strip().casefold() == wanted for p in admitted):
        lockout.record_failure(
            db, phone_key, now,
            threshold=config.login_lock_threshold, lock_minutes=config.login_lock_minutes,
        )
        station_failure()
        raise Unauthorized(_BAD_LOGIN)
    lockout.clear(db, phone_key)
    db.execute(
        delete(GuardianSession).where(
            _same_station(station_id),
            GuardianSession.last_seen_at < now - timedelta(seconds=config.guardian_idle_seconds),
        )
    )
    raw, hashed = new_token()
    db.add(
        GuardianSession(
            token_hash=hashed, phone=number, station_id=station_id,
            created_at=now, last_seen_at=now,
        )
    )
    db.flush()
    return raw, guardian_babies(db, number)


def authenticate(
    db: Session, clock: Clock, config: Config, *, station: Station | None, raw_token: str | None
) -> GuardianSession:
    """세션의 제출처와 요청의 제출처(없으면 None)가 같아야 한다. 일반 기기·제출처 세션은 섞이지 않는다."""
    if not raw_token:
        raise Unauthorized(_NEED_LOGIN)
    station_id = station.id if station is not None else None
    session = db.scalar(
        select(GuardianSession).where(
            GuardianSession.token_hash == hash_token(raw_token),
            _same_station(station_id),
        )
    )
    if session is None:
        raise Unauthorized(_NEED_LOGIN)
    now = clock.now()
    if now - session.last_seen_at > timedelta(seconds=config.guardian_idle_seconds):
        db.delete(session)
        db.flush()
        raise Unauthorized(_NEED_LOGIN)
    session.last_seen_at = now
    return session


def logout(db: Session, session: GuardianSession) -> None:
    db.delete(session)
