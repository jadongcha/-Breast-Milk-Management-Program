import pytest
from sqlalchemy import select

from conftest import auth
from milkbank import staff as staff_service
from milkbank.config import Config
from milkbank.db import make_engine, make_session_factory
from milkbank.models import Role, Staff


def _new_nurse(app, username="nurse1"):
    with app.state.session_factory() as db:
        _, temp = staff_service.create_staff(
            db, app.state.clock, username=username, name="간호사", role=Role.NURSE
        )
        db.commit()
    return temp


def _login(client, temp, username="nurse1"):
    r = client.post("/auth/login", json={"username": username, "password": temp})
    assert r.status_code == 200
    return r.json()["token"]


def test_login_with_temp_password_requires_change(client, app):
    temp = _new_nurse(app)
    r = client.post("/auth/login", json={"username": "nurse1", "password": temp})
    assert r.status_code == 200 and r.json()["must_change_password"] is True
    assert r.json()["staff"]["username"] == "nurse1"
    tok = r.json()["token"]
    r = client.get("/auth/check", headers=auth(tok))
    assert r.status_code == 403 and r.json()["detail"] == "비밀번호를 먼저 변경해야 합니다"
    assert client.get("/auth/me", headers=auth(tok)).status_code == 200


def test_change_password_then_access(client, app):
    temp = _new_nurse(app)
    tok = _login(client, temp)
    r = client.post(
        "/auth/password",
        json={"current_password": temp, "new_password": "newpass123"},
        headers=auth(tok),
    )
    assert r.status_code == 204
    # 바로 다음 요청에서 변경 결과가 보여야 한다(커밋이 응답보다 먼저).
    assert client.get("/auth/check", headers=auth(tok)).status_code == 204
    assert client.post(
        "/auth/login", json={"username": "nurse1", "password": "newpass123"}
    ).status_code == 200


@pytest.mark.parametrize(
    "new,msg",
    [
        ("short1", "비밀번호는 8자 이상이어야 합니다"),
        ("onlyletters", "비밀번호에 영문과 숫자를 모두 포함해야 합니다"),
    ],
)
def test_change_password_policy(client, app, new, msg):
    temp = _new_nurse(app)
    tok = _login(client, temp)
    r = client.post(
        "/auth/password",
        json={"current_password": temp, "new_password": new},
        headers=auth(tok),
    )
    assert r.status_code == 400 and r.json()["detail"] == msg


def test_change_password_wrong_current(client, app):
    temp = _new_nurse(app)
    tok = _login(client, temp)
    r = client.post(
        "/auth/password",
        json={"current_password": "wrong1234", "new_password": "newpass123"},
        headers=auth(tok),
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "현재 비밀번호가 올바르지 않습니다"


def test_change_password_same_as_current(client, app):
    temp = _new_nurse(app)
    tok = _login(client, temp)
    r = client.post(
        "/auth/password",
        json={"current_password": temp, "new_password": temp},
        headers=auth(tok),
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "새 비밀번호는 현재 비밀번호와 달라야 합니다"


def test_login_wrong_password(client, app):
    _new_nurse(app)
    msg = "아이디 또는 비밀번호가 올바르지 않습니다"
    r = client.post("/auth/login", json={"username": "nurse1", "password": "nope12345"})
    assert r.status_code == 401 and r.json()["detail"] == msg
    r = client.post("/auth/login", json={"username": "ghost", "password": "nope12345"})
    assert r.status_code == 401 and r.json()["detail"] == msg


def test_login_inactive_account(client, app):
    temp = _new_nurse(app)
    with app.state.session_factory() as db:
        db.scalar(select(Staff).where(Staff.username == "nurse1")).active = False
        db.commit()
    r = client.post("/auth/login", json={"username": "nurse1", "password": temp})
    assert r.status_code == 401
    assert r.json()["detail"] == "아이디 또는 비밀번호가 올바르지 않습니다"


def test_idle_logout_after_10_minutes(client, app, clock, nurse_token):
    clock.advance(minutes=10)
    assert client.get("/auth/me", headers=auth(nurse_token)).status_code == 200
    clock.advance(minutes=10, seconds=1)
    r = client.get("/auth/me", headers=auth(nurse_token))
    assert r.status_code == 401
    assert r.json()["detail"] == "세션이 만료되었습니다. 다시 로그인해 주세요"


def test_activity_extends_session(client, clock, nurse_token):
    for _ in range(3):
        clock.advance(minutes=9)
        assert client.get("/auth/me", headers=auth(nurse_token)).status_code == 200


def test_logout_invalidates_token(client, nurse_token):
    assert client.post("/auth/logout", headers=auth(nurse_token)).status_code == 204
    r = client.get("/auth/me", headers=auth(nurse_token))
    assert r.status_code == 401 and r.json()["detail"] == "로그인이 필요합니다"


def test_no_token(client):
    r = client.get("/auth/me")
    assert r.status_code == 401 and r.json()["detail"] == "로그인이 필요합니다"


def test_cli_creates_admin(tmp_path, monkeypatch, capsys):
    from milkbank.cli import main

    db_path = tmp_path / "c.db"
    monkeypatch.setenv("MILKBANK_DB", str(db_path))
    monkeypatch.setattr("sys.argv", ["milkbank-admin", "--username", "boss", "--name", "관리자"])
    main()
    assert "임시 비밀번호: " in capsys.readouterr().out
    engine = make_engine(db_path)
    try:
        with make_session_factory(engine)() as db:
            s = db.scalar(select(Staff).where(Staff.username == "boss"))
            assert s.role == Role.ADMIN and s.must_change_password is True
    finally:
        engine.dispose()


def test_live_session_of_deactivated_staff_is_rejected(client, app, nurse_token):
    assert client.get("/auth/me", headers=auth(nurse_token)).status_code == 200
    with app.state.session_factory() as db:
        db.scalar(select(Staff).where(Staff.username == "nurse1")).active = False
        db.commit()
    r = client.get("/auth/me", headers=auth(nurse_token))
    assert r.status_code == 401 and r.json()["detail"] == "로그인이 필요합니다"
