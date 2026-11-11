"""로그인 실패 잠금(직원·보호자·제출처 공용). 저장 표는 LoginAttempt(username 칸이 키).

키 규칙: 직원은 아이디 그대로, 보호자는 "guardian:{전화번호 해시}", 제출처는 "station:{id}".
"""
from datetime import datetime, timedelta

from sqlalchemy import delete
from sqlalchemy.orm import Session

from .errors import TooManyRequests
from .models import LoginAttempt


def ensure_not_locked(db: Session, key: str, now: datetime, message: str) -> None:
    attempt = db.get(LoginAttempt, key)
    if attempt is not None and attempt.locked_until is not None and now < attempt.locked_until:
        raise TooManyRequests(message)


def record_failure(
    db: Session,
    key: str,
    now: datetime,
    *,
    threshold: int,
    lock_minutes: int,
    window_minutes: int | None = None,
) -> None:
    attempt = db.get(LoginAttempt, key)
    if attempt is None:
        attempt = LoginAttempt(username=key, failed_count=1)
        db.add(attempt)
    elif attempt.locked_until is not None:
        # 잠금이 끝난 기록: 새로 센다.
        attempt.failed_count = 1
        attempt.locked_until = None
    elif (
        window_minutes is not None
        and attempt.last_failed_at is not None
        and now - attempt.last_failed_at > timedelta(minutes=window_minutes)
    ):
        attempt.failed_count = 1
    else:
        attempt.failed_count += 1
    attempt.last_failed_at = now
    if attempt.failed_count >= threshold:
        attempt.locked_until = now + timedelta(minutes=lock_minutes)
        attempt.failed_count = 0
    db.flush()


def clear(db: Session, key: str) -> None:
    db.execute(delete(LoginAttempt).where(LoginAttempt.username == key))
