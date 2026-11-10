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
from alembic.operations import Operations

from milkbank.migrate import (
    LegacyDatabaseError,
    UnknownRevisionError,
    _alembic_config,
    backup_db,
    foreign_keys_off,
    migrate,
)
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


def _split_top_level(body: str) -> frozenset[str]:
    parts, depth, cur = [], 0, []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return frozenset(" ".join(p.split()) for p in parts)


def _normalize(kind: str, sql: str):
    if kind == "table":
        head, body = sql.split("(", 1)
        head = " ".join(head.split()).replace('"', "")
        return (head, _split_top_level(body.rsplit(")", 1)[0]))
    return " ".join(sql.split())


def _schema(engine) -> dict:
    with engine.connect() as conn:
        rows = conn.execute(
            text(
                "SELECT type, name, tbl_name, sql FROM sqlite_master"
                " WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'"
                " AND name != 'alembic_version'"
            )
        ).all()
    return {(t, n, tbl): _normalize(t, sql) for t, n, tbl, sql in rows}


def _diff(a: dict, b: dict) -> bool:
    return a != b


def test_schema_sql_identical_to_create_all(tmp_path, clock):
    p1, p2 = tmp_path / "a.db", tmp_path / "b.db"
    e1, e2 = make_engine(p1), make_engine(p2)
    try:
        Base.metadata.create_all(e1)
        migrate(e2, p2, clock.now())
        s1, s2 = _schema(e1), _schema(e2)
        assert s1.keys() == s2.keys()
        assert not _diff(s1, s2), {k: (s1[k], s2[k]) for k in s1 if s1[k] != s2[k]}
        assert ("index", "ux_stock_record_cancel_target", "stock_record") in s2
        assert "WHERE kind = 'cancel'" in s2[("index", "ux_stock_record_cancel_target", "stock_record")]
    finally:
        e1.dispose()
        e2.dispose()


@pytest.mark.parametrize(
    "old,new",
    [
        ("'cancel')", "'cancel', 'extra')"),  # (a) enum에 멤버가 늘었는데 migration 없음
        (" WHERE kind = 'cancel'", ""),  # (b) 부분 인덱스에서 WHERE가 빠짐
    ],
)
def test_schema_comparison_detects_differences(tmp_path, clock, old, new):
    path = tmp_path / "c.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        expected = _schema(eng)
        mutated = {}
        for key, val in expected.items():
            if key[0] == "table":
                mutated[key] = (val[0], frozenset(c.replace(old, new) for c in val[1]))
            else:
                mutated[key] = val.replace(old, new)
        assert _diff(expected, mutated)
    finally:
        eng.dispose()


def test_normalize_strips_quotes_in_table_header():
    plain = _normalize("table", "CREATE TABLE stock_record (id INTEGER NOT NULL, PRIMARY KEY (id))")
    quoted = _normalize(
        "table", 'CREATE TABLE "stock_record" (' + chr(10) + chr(9) + "id INTEGER NOT NULL, " + chr(10) + chr(9) + "PRIMARY KEY (id)" + chr(10) + ")"
    )
    assert plain == quoted


def _rebuild_patient(conn):
    mctx = MigrationContext.configure(conn, opts={"render_as_batch": True})
    with mctx.begin_transaction():
        with Operations(mctx).batch_alter_table("patient") as batch:
            batch.alter_column("bed", nullable=True)


def test_batch_rebuild_fails_without_helper(seeded_engine):
    with seeded_engine.connect() as conn:
        with pytest.raises(IntegrityError):
            _rebuild_patient(conn)


def test_batch_rebuild_works_with_helper(seeded_engine):
    with seeded_engine.connect() as conn:
        with foreign_keys_off(conn):
            _rebuild_patient(conn)
        assert conn.exec_driver_sql("SELECT count(*) FROM patient").scalar() == 1
        assert conn.exec_driver_sql("SELECT count(*) FROM stock_record").scalar() == 1
        conn.rollback()


def test_foreign_keys_back_on_after_helper(seeded_engine):
    with seeded_engine.connect() as conn:
        conn.rollback()
        with foreign_keys_off(conn):
            assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar() == 0
        assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        conn.rollback()


def test_helper_raises_on_dangling_foreign_key(seeded_engine):
    with seeded_engine.connect() as conn:
        with pytest.raises(RuntimeError, match="stock_record"):
            with foreign_keys_off(conn):
                conn.exec_driver_sql("UPDATE stock_record SET patient_id = 999")
                conn.commit()
        assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        conn.rollback()


def test_unknown_revision_rejected_before_backup(tmp_path, clock):
    path = tmp_path / "u.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        with eng.connect() as conn:
            conn.execute(text("UPDATE alembic_version SET version_num = '9999'"))
            conn.commit()
        with pytest.raises(UnknownRevisionError) as e:
            migrate(eng, path, clock.now())
        assert str(e.value) == (
            "이 DB는 더 새로운 버전의 프로그램에서 만들어졌습니다. 프로그램을 업데이트하세요"
        )
        assert not (tmp_path / "backups").exists()
    finally:
        eng.dispose()


def test_backup_goes_to_config_backups_dir(tmp_path, clock):
    path = tmp_path / "bk.db"
    eng = make_engine(path)
    try:
        migrate(eng, path, clock.now())
        with eng.connect() as conn:
            command.downgrade(_alembic_config(conn), "base")
            conn.commit()
        from milkbank.config import Config

        backup = migrate(eng, path, clock.now())
        assert backup.parent == Config(db_path=path).backups_dir
    finally:
        eng.dispose()
