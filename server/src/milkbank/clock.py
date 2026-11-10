from datetime import UTC, datetime, timedelta
from typing import Protocol
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")


class Clock(Protocol):
    def now(self) -> datetime:
        """tz-aware UTC 현재 시각."""
        ...


class SystemClock:
    def now(self) -> datetime:
        return datetime.now(UTC)


class FakeClock:
    def __init__(self, start: datetime) -> None:
        self._now = start

    def now(self) -> datetime:
        return self._now

    def advance(self, *, minutes: int = 0, seconds: int = 0) -> None:
        self._now += timedelta(minutes=minutes, seconds=seconds)
