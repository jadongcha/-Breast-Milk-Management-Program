import sqlite3
from datetime import datetime
from pathlib import Path

from alembic import command
from alembic.config import Config as AlembicConfig
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Connection, Engine, inspect


class LegacyDatabaseError(Exception):
    def __init__(self) -> None:
        super().__init__("1단계 테스트용 DB는 지원하지 않습니다. 파일을 지우고 다시 시작하세요")


def _alembic_config(connection: Connection) -> AlembicConfig:
    cfg = AlembicConfig()
    cfg.set_main_option("script_location", str(Path(__file__).parent / "migrations"))
    cfg.attributes["connection"] = connection
    return cfg


def backup_db(src: Path, dest: Path) -> None:
    """sqlite 백업 API로 복사한다(WAL에 남은 최신 내용 포함)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    source = sqlite3.connect(src)
    target = sqlite3.connect(dest)
    try:
        source.backup(target)
    finally:
        target.close()
        source.close()


def migrate(engine: Engine, db_path: Path, now: datetime) -> Path | None:
    db_path = Path(db_path)
    # SQLite는 연결하면 파일을 만들므로, 처음 연결하기 전에 존재 여부를 본다.
    existed = db_path.exists() and db_path.stat().st_size > 0
    backup: Path | None = None
    with engine.connect() as conn:
        cfg = _alembic_config(conn)
        head = ScriptDirectory.from_config(cfg).get_current_head()
        tables = set(inspect(conn).get_table_names())
        if "alembic_version" not in tables and "staff" in tables:
            raise LegacyDatabaseError()
        current = MigrationContext.configure(conn).get_current_revision()
        conn.rollback()
        if current == head:
            return None
        if existed:
            backup = db_path.parent / "backups" / f"milkbank-{now:%Y%m%dT%H%M%SZ}-before-{head}.db"
            backup_db(db_path, backup)
        command.upgrade(cfg, "head")
        conn.commit()
    return backup
