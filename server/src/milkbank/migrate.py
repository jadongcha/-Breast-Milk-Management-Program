import sqlite3
from contextlib import contextmanager
from datetime import datetime
from collections.abc import Iterator
from pathlib import Path

from alembic import command
from alembic.config import Config as AlembicConfig
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Connection, Engine, inspect

from .config import Config


class LegacyDatabaseError(Exception):
    def __init__(self) -> None:
        super().__init__("1단계 테스트용 DB는 지원하지 않습니다. 파일을 지우고 다시 시작하세요")


class UnknownRevisionError(Exception):
    def __init__(self) -> None:
        super().__init__(
            "이 DB는 더 새로운 버전의 프로그램에서 만들어졌습니다. 프로그램을 업데이트하세요"
        )


def check_foreign_keys(connection: Connection) -> None:
    """외래 키가 깨진 행이 있으면 RuntimeError를 낸다(열려 있는 트랜잭션 안에서 부르면 되돌려진다)."""
    rows = connection.exec_driver_sql("PRAGMA foreign_key_check").fetchall()
    if rows:
        tables = sorted({str(r[0]) for r in rows})
        raise RuntimeError(f"외래 키가 깨진 행이 있습니다: {', '.join(tables)}")


@contextmanager
def foreign_keys_off(connection: Connection) -> Iterator[None]:
    """표 재생성(batch) 마이그레이션용: 외래 키 검사를 끄고, 끝나면 검사 후 다시 켠다.

    SQLite는 트랜잭션 안에서 PRAGMA foreign_keys를 무시하므로 열려 있는 트랜잭션을 먼저 닫는다.
    마이그레이션 트랜잭션은 이 블록 안에서 열어야 한다. 블록을 나갈 때(마지막 안전장치로)
    foreign_key_check를 다시 하고, 풀에 돌려보내기 전에 외래 키를 켠다.
    """
    if connection.in_transaction():
        connection.rollback()
    driver = connection.connection.driver_connection
    driver.execute("PRAGMA foreign_keys=OFF")
    try:
        yield
        check_foreign_keys(connection)
    finally:
        if connection.in_transaction():
            connection.rollback()
        driver.execute("PRAGMA foreign_keys=ON")


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
        script = ScriptDirectory.from_config(cfg)
        head = script.get_current_head()
        tables = set(inspect(conn).get_table_names())
        if "alembic_version" not in tables and "staff" in tables:
            raise LegacyDatabaseError()
        current = MigrationContext.configure(conn).get_current_revision()
        conn.rollback()
        known = {r.revision for r in script.walk_revisions()}
        if current is not None and current not in known:
            raise UnknownRevisionError()
        if current == head:
            return None
        if existed:
            backups_dir = Config(db_path=db_path).backups_dir
            backup = backups_dir / f"milkbank-{now:%Y%m%dT%H%M%SZ}-before-{head}.db"
            backup_db(db_path, backup)
        command.upgrade(cfg, "head")
        conn.commit()
    return backup
