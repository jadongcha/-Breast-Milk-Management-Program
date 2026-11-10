"""직원 서비스: 생성, 로그인, 세션, 비밀번호."""
from datetime import timedelta

from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .clock import Clock
from .errors import BadRequest, Conflict, NotFound, Unauthorized
from .models import AuthSession, Role, Staff
from .security import (
    check_password_policy,
    generate_temp_password,
    hash_password,
    hash_token,
    new_token,
    verify_password,
)

_BAD_LOGIN = "아이디 또는 비밀번호가 올바르지 않습니다"


def create_staff(
    db: Session, clock: Clock, *, username: str, name: str, role: Role
) -> tuple[Staff, str]:
    username = username.strip()
    if not username:
        raise BadRequest("아이디를 입력해 주세요")
    if db.scalar(select(Staff.id).where(Staff.username == username)) is not None:
        raise Conflict("이미 사용 중인 아이디입니다")
    temp = generate_temp_password()
    staff = Staff(
        username=username,
        name=name,
        role=role,
        password_hash=hash_password(temp),
        must_change_password=True,
        active=True,
        created_at=clock.now(),
    )
    db.add(staff)
    try:
        db.flush()
    except IntegrityError:
        raise Conflict("이미 사용 중인 아이디입니다") from None
    return staff, temp


def login(db: Session, clock: Clock, username: str, password: str) -> tuple[Staff, str]:
    staff = db.scalar(select(Staff).where(Staff.username == username.strip()))
    if (
        staff is None
        or not staff.active
        or not verify_password(staff.password_hash, password)
    ):
        raise Unauthorized(_BAD_LOGIN)
    raw, hashed = new_token()
    now = clock.now()
    db.add(AuthSession(token_hash=hashed, staff_id=staff.id, created_at=now, last_seen_at=now))
    db.flush()
    return staff, raw


def authenticate(
    db: Session, clock: Clock, raw_token: str | None, idle_minutes: int
) -> Staff:
    if not raw_token:
        raise Unauthorized("로그인이 필요합니다")
    session = db.scalar(
        select(AuthSession).where(AuthSession.token_hash == hash_token(raw_token))
    )
    if session is None:
        raise Unauthorized("로그인이 필요합니다")
    now = clock.now()
    if now - session.last_seen_at > timedelta(minutes=idle_minutes):
        db.delete(session)
        raise Unauthorized("세션이 만료되었습니다. 다시 로그인해 주세요")
    staff = db.get(Staff, session.staff_id)
    if staff is None or not staff.active:
        raise Unauthorized("로그인이 필요합니다")
    session.last_seen_at = now
    return staff


def logout(db: Session, raw_token: str) -> None:
    db.execute(delete(AuthSession).where(AuthSession.token_hash == hash_token(raw_token)))


def change_password(db: Session, staff: Staff, current: str, new: str) -> None:
    if not verify_password(staff.password_hash, current):
        raise BadRequest("현재 비밀번호가 올바르지 않습니다")
    problem = check_password_policy(new)
    if problem:
        raise BadRequest(problem)
    if new == current:
        raise BadRequest("새 비밀번호는 현재 비밀번호와 달라야 합니다")
    staff.password_hash = hash_password(new)
    staff.must_change_password = False


def delete_sessions(db: Session, staff_id: int) -> None:
    db.execute(delete(AuthSession).where(AuthSession.staff_id == staff_id))


def list_staff(db: Session) -> list[Staff]:
    return list(db.scalars(select(Staff).order_by(Staff.username)))


def _get_staff(db: Session, staff_id: int) -> Staff:
    staff = db.get(Staff, staff_id)
    if staff is None:
        raise NotFound("직원을 찾을 수 없습니다")
    return staff


def reset_password(db: Session, staff_id: int) -> str:
    staff = _get_staff(db, staff_id)
    temp = generate_temp_password()
    staff.password_hash = hash_password(temp)
    staff.must_change_password = True
    delete_sessions(db, staff.id)
    return temp


def set_active(db: Session, actor: Staff, staff_id: int, active: bool) -> Staff:
    staff = _get_staff(db, staff_id)
    if not active and staff.id == actor.id:
        raise BadRequest("본인 계정은 비활성화할 수 없습니다")
    staff.active = active
    if not active:
        delete_sessions(db, staff.id)
    return staff
