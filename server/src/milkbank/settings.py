"""병원 설정: 기본값과 범위. 저장된 값이 없으면 기본값을 쓴다."""
from dataclasses import dataclass, fields

from sqlalchemy import select
from sqlalchemy.orm import Session

from .errors import BadRequest
from .models import Setting


@dataclass(frozen=True)
class Settings:
    days_threshold: int = 2
    packs_threshold: int = 3
    avg_days: int = 3
    staff_idle_minutes: int = 10
    retention_days: int = 30


# 키 -> (표시 이름, 최소, 최대)
_RULES: dict[str, tuple[str, int, int]] = {
    "days_threshold": ("남은 일수 기준", 1, 30),
    "packs_threshold": ("신규 환자 팩 수 기준", 1, 50),
    "avg_days": ("평균 계산 기간", 1, 14),
    "staff_idle_minutes": ("직원 자동 로그아웃", 1, 60),
    "retention_days": ("개인정보 보관 기간", 1, 365),
}


def get(db: Session) -> Settings:
    stored = {s.key: s.value for s in db.scalars(select(Setting))}
    known = {f.name for f in fields(Settings)}
    return Settings(**{k: v for k, v in stored.items() if k in known})


def update(db: Session, changes: dict[str, int]) -> Settings:
    # 첫 질의로 쓰기 잠금을 잡고, 모두 검사한 뒤에 저장한다.
    current = get(db)
    for key, value in changes.items():
        name, lo, hi = _RULES[key]
        if not lo <= value <= hi:
            raise BadRequest(f"{name}은(는) {lo}~{hi} 사이여야 합니다")
    for key, value in changes.items():
        row = db.get(Setting, key)
        if row is None:
            db.add(Setting(key=key, value=value))
        else:
            row.value = value
    db.flush()
    return get(db) if changes else current
