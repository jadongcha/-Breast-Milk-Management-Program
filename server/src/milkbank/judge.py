"""부족 판정: 순수 함수. 남은 일수(평균 사용량 기준) 또는 신규 환자의 팩 수로 판단한다."""
from dataclasses import dataclass
from datetime import date
from typing import Literal

Status = Literal["enough", "low", "empty"]


@dataclass(frozen=True)
class Judgement:
    daily_avg: float | None
    days_left: float | None
    status: Status


def judge(
    *,
    balance: int,
    first_use_day: date | None,
    recent_use_packs: int,
    today: date,
    avg_days: int,
    days_threshold: int,
    packs_threshold: int,
) -> Judgement:
    daily_avg: float | None = None
    used_days = (today - first_use_day).days if first_use_day else 0
    if first_use_day and used_days >= avg_days:
        daily_avg = recent_use_packs / avg_days
        if daily_avg > 0:
            days_left = balance / daily_avg
            if balance == 0:
                return Judgement(daily_avg, days_left, "empty")
            # 실수 오차를 피하려고 정수로 비교한다: balance/(recent/avg_days) <= days_threshold
            is_low = balance * avg_days <= days_threshold * recent_use_packs
            status: Status = "low" if is_low else "enough"
            return Judgement(daily_avg, days_left, status)
    if balance == 0:
        return Judgement(daily_avg, None, "empty")
    status = "low" if balance <= packs_threshold else "enough"
    return Judgement(daily_avg, None, status)
