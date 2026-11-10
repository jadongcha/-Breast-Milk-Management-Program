import sqlite3
from contextlib import closing

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError

from milkbank import cli
from milkbank import patients as patient_service
from milkbank import staff as staff_service
from milkbank.db import make_engine
from milkbank.migrate import LegacyDatabaseError, _alembic_config, backup_db, migrate
from milkbank.models import Base, RecordKind, Role, StockRecord
from milkbank.schemas import PatientCreate

LEGACY_MSG = "1단계 테스트용 DB는 지원하지 않습니다. 파일을 지우고 다시 시작하세요"


def _version(engine):
    with engine.connect() as conn:
        return conn.execute(text("SELECT version_num FROM alembic_version")).scalar()


def test_new_db_is_at_head(tmp_path, clock):
    path = tmp_path / "n.db"
    eng = make_engine(path)
    try:
        assert migrate(eng, path, clock.now()) is None
        assert _version(eng) == "0001"
        tables = set(inspect(eng).get_table_names())
        assert {"staff", "auth_session", "patient", "stock_record", "login_attempt"} <= tables
    finally:
        eng.dispose()


def test_models_match_migrations(tmp_path, clock):
    path = tmp_path / "m.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        with eng.connect() as conn:
            ctx = MigrationContext.configure(conn, opts={"compare_type": True})
            assert compare_metadata(ctx, Base.metadata) == []
    finally:
        eng.dispose()


def test_migrate_twice_is_noop(tmp_path, clock):
    path = tmp_path / "t.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        assert migrate(eng, path, clock.now()) is None
        backups = tmp_path / "backups"
        assert not backups.exists() or list(backups.iterdir()) == []
    finally:
        eng.dispose()


def test_pending_upgrade_makes_backup(tmp_path, clock):
    path = tmp_path / "p.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        with eng.connect() as conn:
            command.downgrade(_alembic_config(conn), "base")
            conn.commit()
        backup = migrate(eng, path, clock.now())
        assert backup is not None and backup.exists()
        assert backup.name == "milkbank-20261110T000000Z-before-0001.db"
        assert _version(eng) == "0001"
    finally:
        eng.dispose()


def test_legacy_db_rejected(tmp_path, clock):
    path = tmp_path / "l.db"
    eng = make_engine(path)
    try:
        Base.metadata.create_all(eng)
        with pytest.raises(LegacyDatabaseError) as e:
            migrate(eng, path, clock.now())
        assert str(e.value) == LEGACY_MSG
    finally:
        eng.dispose()


def test_backup_includes_wal_data(tmp_path, clock):
    path = tmp_path / "w.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        conn = eng.connect()  # 열어 둔 연결이 체크포인트를 막는다
        try:
            conn.execute(
                text(
                    "INSERT INTO staff (username, name, role, password_hash,"
                    " must_change_password, active, created_at)"
                    " VALUES ('a','a','admin','x',0,1,'2026-11-10 00:00:00')"
                )
            )
            conn.commit()
            dest = tmp_path / "b.db"
            backup_db(path, dest)
        finally:
            conn.close()
        with closing(sqlite3.connect(dest)) as c:
            assert c.execute("SELECT count(*) FROM staff").fetchone()[0] == 1
    finally:
        eng.dispose()


def test_double_cancel_blocked_by_index(db, clock):
    s, _ = staff_service.create_staff(db, clock, username="n1", name="n1", role=Role.NURSE)
    p = patient_service.create_patient(
        db,
        clock,
        PatientCreate(
            reg_no="R1", baby_name="b", bed="1", guardian_name="g", guardian_phone="01012345678"
        ),
    )
    intake = StockRecord(
        patient_id=p.id, kind=RecordKind.INTAKE, count=3, staff_id=s.id, created_at=clock.now()
    )
    db.add(intake)
    db.flush()

    def cancel():
        return StockRecord(
            patient_id=p.id,
            kind=RecordKind.CANCEL,
            count=3,
            target_id=intake.id,
            staff_id=s.id,
            created_at=clock.now(),
        )

    db.add(cancel())
    db.flush()
    db.add(cancel())
    with pytest.raises(IntegrityError):
        db.flush()
    db.rollback()


def test_cli_rejects_legacy_db(tmp_path, monkeypatch, capsys):
    path = tmp_path / "legacy.db"
    eng = make_engine(path)
    Base.metadata.create_all(eng)
    eng.dispose()
    monkeypatch.setenv("MILKBANK_DB", str(path))
    monkeypatch.setattr("sys.argv", ["x", "--username", "a", "--name", "b"])
    with pytest.raises(SystemExit) as e:
        cli.main()
    assert e.value.code == 1
    assert LEGACY_MSG in capsys.readouterr().err
