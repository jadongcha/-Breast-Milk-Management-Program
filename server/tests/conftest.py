from datetime import UTC, datetime

import pytest
from sqlalchemy.orm import Session

from milkbank.clock import FakeClock
from milkbank.db import make_engine, make_session_factory
from milkbank.migrate import migrate


@pytest.fixture
def engine(tmp_path, clock):
    path = tmp_path / "test.db"
    eng = make_engine(path)
    migrate(eng, path, clock.now())
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


from milkbank.config import Config  # noqa: E402
from milkbank.main import create_app  # noqa: E402
from milkbank.models import Role  # noqa: E402
from milkbank import staff as staff_service  # noqa: E402


@pytest.fixture
def app(tmp_path, clock):
    application = create_app(Config(db_path=tmp_path / "t.db"), clock)
    yield application
    application.state.engine.dispose()


@pytest.fixture
def client(app):
    import warnings

    # starlette 1.x는 TestClient에 httpx2를 권장한다. 의존성은 httpx로 두고 이 경고만 무시한다.
    with warnings.catch_warnings():
        warnings.filterwarnings(
            "ignore", message=".*starlette.testclient.*", category=Warning
        )
        from fastapi.testclient import TestClient

    with TestClient(app) as c:
        yield c


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def make_staff(client, app, role: Role, username: str | None = None) -> tuple[int, str]:
    """직원을 만들고 로그인한 뒤 비밀번호를 newpass123으로 바꿔 (id, token)을 돌려준다."""
    username = username or f"{role.value}-{id(object())}"
    # 세션은 짧게 쓰고 닫은 뒤에 클라이언트를 호출한다(BEGIN IMMEDIATE 잠금 방지).
    with app.state.session_factory() as db:
        staff, temp = staff_service.create_staff(
            db, app.state.clock, username=username, name=username, role=role
        )
        db.commit()
        staff_id = staff.id
    r = client.post("/auth/login", json={"username": username, "password": temp})
    assert r.status_code == 200, r.text
    token = r.json()["token"]
    r = client.post(
        "/auth/password",
        json={"current_password": temp, "new_password": "newpass123"},
        headers=auth(token),
    )
    assert r.status_code == 204, r.text
    return staff_id, token


@pytest.fixture
def admin_token(client, app):
    return make_staff(client, app, Role.ADMIN, "admin1")[1]


@pytest.fixture
def nurse_token(client, app):
    return make_staff(client, app, Role.NURSE, "nurse1")[1]
