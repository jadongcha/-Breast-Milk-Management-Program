"""마이그레이션 초안을 자동으로 만든다: uv run python scripts/new_migration.py "설명"

임시 DB를 최신 버전까지 올린 뒤, models.py와 다른 부분을 migrations/versions/에 초안으로 쓴다.
초안은 반드시 사람이 검토한다(enum CHECK 이름, 표 재생성 등).
"""
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

from alembic import command

from milkbank.db import make_engine
from milkbank.migrate import _alembic_config, migrate


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit('사용법: uv run python scripts/new_migration.py "설명"')
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "draft.db"
        engine = make_engine(path)
        try:
            migrate(engine, path, datetime.now(UTC))
            with engine.connect() as conn:
                command.revision(_alembic_config(conn), message=sys.argv[1], autogenerate=True)
        finally:
            engine.dispose()


if __name__ == "__main__":
    main()
