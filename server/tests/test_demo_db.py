import importlib.util
from datetime import date
from pathlib import Path

import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from milkbank import packs, staff as staff_service, stock
from milkbank.clock import FakeClock
from milkbank.db import make_engine
from milkbank.migrate import MigrationContext, _alembic_config, ScriptDirectory
from milkbank.models import (
    Pack,
    Patient,
    RecordKind,
    Role,
    Staff,
    StockRecord,
    Submission,
    SubmissionStatus,
)

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "make_demo_db.py"
TODAY = date(2026, 11, 15)


def _load():
    spec = importlib.util.spec_from_file_location("make_demo_db", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("demo")
    db_path, accounts = d / "data" / "milkbank.db", d / "데모계정.txt"
    passwords = _load().build_demo(db_path, accounts, TODAY)
    engine = make_engine(db_path)
    yield engine, db_path, accounts, passwords
    engine.dispose()


def test_db_is_at_migration_head(built):
    engine, *_ = built
    with engine.connect() as conn:
        head = ScriptDirectory.from_config(_alembic_config(conn)).get_current_head()
        assert MigrationContext.configure(conn).get_current_revision() == head


def test_eight_babies_with_phones(built):
    engine, *_ = built
    with Session(engine) as db:
        rows = db.scalars(select(Patient).order_by(Patient.reg_no)).all()
        assert [p.reg_no for p in rows] == [f"DEMO-0{i}" for i in range(1, 9)]
        assert [p.guardian_phone for p in rows] == [f"010123456{i:02d}" for i in range(1, 9)]
        assert [p.bed for p in rows] == [str(i) for i in range(1, 9)]


def test_two_accounts_can_log_in_immediately(built):
    engine, _, accounts, passwords = built
    clock = FakeClock(_load_now(engine))
    with Session(engine) as db:
        staff = db.scalars(select(Staff)).all()
        assert sorted((s.username, s.role) for s in staff) == [
            ("admin", Role.ADMIN),
            ("nurse1", Role.NURSE),
        ]
        assert not any(s.must_change_password for s in staff)
        for username in ("admin", "nurse1"):
            s, token = staff_service.login(db, clock, username, passwords[username])
            assert s.username == username and token
        db.rollback()
    text = accounts.read_text(encoding="utf-8")
    assert passwords["admin"] in text and passwords["nurse1"] in text
    assert "DEMO-01" in text and "010-1234-5601" in text


def _load_now(engine):
    with Session(engine) as db:
        return db.scalar(select(func.max(StockRecord.created_at))).replace(
            tzinfo=__import__("datetime").UTC
        )


def test_balance_equals_packs_in_stock(built):
    engine, *_ = built
    with Session(engine) as db:
        for p in db.scalars(select(Patient)):
            assert stock.balance(db, p.id) == len(packs.in_stock(db, p.id))
        assert db.scalar(select(func.count()).select_from(Pack)) > 0


def test_has_low_and_empty_babies_and_unknown_ml_packs(built):
    engine, *_ = built
    with Session(engine) as db:
        balances = [stock.balance(db, p.id) for p in db.scalars(select(Patient))]
        assert 0 in balances
        assert any(1 <= b <= 3 for b in balances)
        assert any(b > 3 for b in balances)
        assert db.scalar(select(func.count()).select_from(Pack).where(Pack.ml.is_(None))) > 0
        kinds = {k for (k,) in db.execute(select(StockRecord.kind).distinct())}
        assert {RecordKind.INTAKE, RecordKind.USE, RecordKind.DISCARD} <= kinds


def test_records_span_last_14_days(built):
    engine, *_ = built
    with Session(engine) as db:
        first = db.scalar(select(func.min(StockRecord.created_at)))
        last = db.scalar(select(func.max(StockRecord.created_at)))
        assert (last - first).days >= 13


def test_two_pending_submissions(built):
    engine, *_ = built
    with Session(engine) as db:
        rows = db.scalars(
            select(Submission).where(Submission.status == SubmissionStatus.PENDING)
        ).all()
        assert len(rows) == 2


def test_refuses_to_overwrite_existing_db(built, tmp_path):
    _, db_path, accounts, _ = built
    with pytest.raises(SystemExit):
        _load().build_demo(db_path, tmp_path / "x.txt", TODAY)
