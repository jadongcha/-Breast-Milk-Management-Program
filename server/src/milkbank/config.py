import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Config:
    db_path: Path
    staff_idle_minutes: int = 10
    cancel_window_minutes: int = 10


def load_config() -> Config:
    return Config(db_path=Path(os.environ.get("MILKBANK_DB", "data/milkbank.db")))
