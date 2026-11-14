import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Config:
    db_path: Path
    cancel_window_minutes: int = 10
    login_lock_threshold: int = 5
    login_lock_minutes: int = 10
    station_lock_threshold: int = 20
    station_lock_window_minutes: int = 10
    station_lock_minutes: int = 10
    guardian_idle_seconds: int = 60
    pending_limit: int = 5
    auto_erase_interval_seconds: int = 3600

    @property
    def tls_dir(self) -> Path:
        return self.db_path.parent / "tls"

    @property
    def backups_dir(self) -> Path:
        return self.db_path.parent / "backups"


def load_config() -> Config:
    return Config(db_path=Path(os.environ.get("MILKBANK_DB", "data/milkbank.db")))
