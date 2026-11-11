import shutil

import pytest
from sqlalchemy import inspect, text

from milkbank import migrate as migrate_module
from milkbank.db import make_engine
from milkbank.migrate import migrate

BROKEN = '''"""broken"""
from alembic import op
import sqlalchemy as sa

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table("tmp_x", sa.Column("id", sa.Integer(), nullable=False))
    op.execute(
        "INSERT INTO stock_record (patient_id, kind, count, staff_id, created_at)"
        " VALUES (99999, 'in', 1, 99999, '2026-11-10 00:00:00')"
    )


def downgrade() -> None:
    op.drop_table("tmp_x")
'''


@pytest.fixture
def broken_dir(tmp_path, monkeypatch):
    dest = tmp_path / "migrations_copy"
    shutil.copytree(
        migrate_module.MIGRATIONS_DIR, dest, ignore=shutil.ignore_patterns("__pycache__")
    )
    (dest / "versions" / "0003_broken.py").write_text(BROKEN, encoding="utf-8")
    monkeypatch.setattr(migrate_module, "MIGRATIONS_DIR", dest)
    return dest


def _version(engine):
    with engine.connect() as conn:
        return conn.execute(text("SELECT version_num FROM alembic_version")).scalar()


def test_broken_migration_rolls_back_completely(seeded_engine, tmp_path, clock, broken_dir):
    counts = {}
    with seeded_engine.connect() as conn:
        for t in ("staff", "patient", "stock_record"):
            counts[t] = conn.exec_driver_sql(f"SELECT count(*) FROM {t}").scalar()
    db_path = tmp_path / "test.db"  # engine 픽스처의 DB 파일
    with pytest.raises(RuntimeError, match="외래 키가 깨진 행"):
        migrate(seeded_engine, db_path, clock.now())
    assert _version(seeded_engine) == "0002"
    assert "tmp_x" not in inspect(seeded_engine).get_table_names()
    with seeded_engine.connect() as conn:
        for t, n in counts.items():
            assert conn.exec_driver_sql(f"SELECT count(*) FROM {t}").scalar() == n
        assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1


def test_broken_migration_new_db_leaves_no_partial_schema(tmp_path, clock, broken_dir):
    path = tmp_path / "new.db"
    eng = make_engine(path)
    try:
        with pytest.raises(RuntimeError, match="외래 키가 깨진 행"):
            migrate(eng, path, clock.now())
        assert _version(eng) == "0002"
        assert "tmp_x" not in inspect(eng).get_table_names()
    finally:
        eng.dispose()
