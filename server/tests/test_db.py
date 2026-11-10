from datetime import UTC

from sqlalchemy import inspect

from milkbank.models import Role, Staff


def test_tables_created(engine):
    names = set(inspect(engine).get_table_names())
    assert {"staff", "auth_session", "patient", "stock_record"} <= names


def test_sqlite_pragmas(engine):
    with engine.connect() as c:
        assert c.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"
        assert c.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        assert c.exec_driver_sql("PRAGMA busy_timeout").scalar() == 5000


def test_utc_datetime_roundtrip(db, clock):
    now = clock.now()
    db.add(
        Staff(
            username="nurse1",
            name="김간호",
            role=Role.NURSE,
            password_hash="x",
            must_change_password=True,
            created_at=now,
        )
    )
    db.commit()
    db.expire_all()
    got = db.query(Staff).one()
    assert got.created_at.tzinfo is not None
    assert got.created_at.utcoffset().total_seconds() == 0
    assert got.created_at.astimezone(UTC) == now
    assert got.role is Role.NURSE
    assert got.active is True


def test_fake_clock_advance(clock):
    t0 = clock.now()
    clock.advance(minutes=10, seconds=1)
    assert (clock.now() - t0).total_seconds() == 601


def test_enum_stored_as_value(engine, db, clock):
    db.add(
        Staff(
            username="a",
            name="a",
            role=Role.ADMIN,
            password_hash="x",
            must_change_password=False,
            created_at=clock.now(),
        )
    )
    db.commit()
    with engine.connect() as c:
        assert c.exec_driver_sql("SELECT role FROM staff").scalar() == "admin"
