from datetime import UTC, datetime

import pytest
from sqlalchemy.orm import Session

from milkbank.clock import FakeClock
from milkbank.db import init_db, make_engine, make_session_factory


@pytest.fixture
def engine(tmp_path):
    eng = make_engine(tmp_path / "test.db")
    init_db(eng)
    yield eng
    eng.dispose()


@pytest.fixture
def db(engine):
    session: Session = make_session_factory(engine)()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture
def clock():
    # 한국 시각 2026-11-10 09:00
    return FakeClock(datetime(2026, 11, 10, 0, 0, tzinfo=UTC))
