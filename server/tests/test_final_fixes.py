"""최종 리뷰 수정 사항 테스트."""
import re
from datetime import UTC, datetime

import pytest

from conftest import auth, make_staff
from milkbank.clock import FakeClock
from milkbank.config import Config
from milkbank.main import create_app
from milkbank.models import Role

P = {
    "reg_no": "A123",
    "baby_name": "김아기",
    "bed": "3",
    "guardian_name": "김엄마",
    "guardian_phone": "010-1234-5678",
}


def test_circled_digit_bed_does_not_break_list(client, admin_token):
    r = client.post("/patients", json={**P, "bed": "①"}, headers=auth(admin_token))
    assert r.status_code == 201
    r = client.get("/patients", headers=auth(admin_token))
    assert r.status_code == 200
    assert [p["bed"] for p in r.json()] == ["①"]


def test_fullwidth_phone_rejected(client, admin_token):
    r = client.post(
        "/patients",
        json={**P, "guardian_phone": "01０１２３４５６７８"},
        headers=auth(admin_token),
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "전화번호 형식이 올바르지 않습니다"


def test_cli_duplicate_username_exits_1(tmp_path, monkeypatch, capsys):
    from milkbank.cli import main

    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "c.db"))
    monkeypatch.setattr("sys.argv", ["milkbank-admin", "--username", "boss", "--name", "관리자"])
    main()
    out = capsys.readouterr().out
    assert "임시 비밀번호: " in out
    with pytest.raises(SystemExit) as exc:
        main()
    assert exc.value.code == 1
    cap = capsys.readouterr()
    assert "이미 사용 중인 아이디입니다" in cap.err
    assert "Traceback" not in cap.err


def test_blank_staff_name_rejected(client, admin_token):
    r = client.post(
        "/staff",
        json={"username": "n9", "name": "   ", "role": "nurse"},
        headers=auth(admin_token),
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "이름을 입력해 주세요"


def test_usernames_case_insensitive(client, admin_token):
    r = client.post(
        "/staff",
        json={"username": "Nurse2", "name": "간호사", "role": "nurse"},
        headers=auth(admin_token),
    )
    assert r.status_code == 201
    assert r.json()["staff"]["username"] == "nurse2"
    temp = r.json()["temp_password"]
    r = client.post("/auth/login", json={"username": "NURSE2", "password": temp})
    assert r.status_code == 200
    r = client.post(
        "/staff",
        json={"username": "nurse2", "name": "다른", "role": "nurse"},
        headers=auth(admin_token),
    )
    assert r.status_code == 409
    assert r.json()["detail"] == "이미 사용 중인 아이디입니다"


def test_polling_check_does_not_extend_session(client, clock, nurse_token):
    codes = []
    for _ in range(11):
        clock.advance(minutes=1)
        codes.append(client.get("/auth/check", headers=auth(nurse_token)))
    assert [c.status_code for c in codes[:10]] == [204] * 10
    assert codes[10].status_code == 401
    assert codes[10].json()["detail"] == "세션이 만료되었습니다. 다시 로그인해 주세요"


def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json() == {"status": "ok"}


def test_nurse_cancel_window_message_and_config(client, admin_token, nurse_token, clock):
    pid = client.post("/patients", json=P, headers=auth(admin_token)).json()["id"]
    r = client.post(
        f"/patients/{pid}/records",
        json={"kind": "intake", "count": 2},
        headers=auth(nurse_token),
    )
    rid = r.json()["record"]["id"]
    clock.advance(minutes=5)
    assert client.get("/auth/me", headers=auth(nurse_token)).status_code == 200
    clock.advance(minutes=5, seconds=1)
    r = client.post(f"/records/{rid}/cancel", headers=auth(nurse_token))
    assert r.status_code == 403
    assert r.json()["detail"] == "입력 후 10분이 지나 관리자만 취소할 수 있습니다"


def _protected_routes(app):
    # 이 FastAPI 버전은 라우터를 지연 포함하므로 app.routes 대신 OpenAPI 스키마로 라우트를 찾는다.
    # GET /station/self는 직원이 아니라 제출처 토큰으로 지킨다(test_stations.py).
    public = {("POST", "/auth/login"), ("GET", "/health"), ("GET", "/station/self")}
    out = []
    for path, ops in app.openapi()["paths"].items():
        for method in ops:
            key = (method.upper(), path)
            if key in public:
                continue
            out.append((key[0], re.sub(r"\{[^}]+\}", "1", path)))
    return out


_ANY_STAFF = {("POST", "/auth/logout"), ("POST", "/auth/password"), ("GET", "/auth/me")}


def _is_admin_only(method, path):
    return (
        path.startswith(("/staff", "/stations"))
        or path == "/station/self"
        or (path == "/patients" and method == "POST")
        or (path.startswith("/patients/") and method == "PATCH")
    )


def test_protected_routes_are_discovered(app):
    routes = _protected_routes(app)
    assert ("GET", "/auth/check") in routes and ("POST", "/records/1/cancel") in routes
    assert len(routes) >= 14


def test_all_protected_routes_gated(client, app):
    from sqlalchemy import select

    from milkbank import staff as staff_service

    routes = _protected_routes(app)
    # 비밀번호를 아직 바꾸지 않은 간호사
    with app.state.session_factory() as db:
        _, temp = staff_service.create_staff(
            db, app.state.clock, username="fresh1", name="신규", role=Role.NURSE
        )
        db.commit()
    make_staff(client, app, Role.NURSE, "gate-nurse")

    def login(username, password):
        r = client.post("/auth/login", json={"username": username, "password": password})
        return r.json()["token"]

    for method, path in routes:
        # /auth/logout 등이 토큰을 지우므로 라우트마다 새로 로그인한다.
        fresh = login("fresh1", temp)
        nurse = login("gate-nurse", "newpass123")
        kw = {"json": {}} if method in ("POST", "PATCH") else {}
        r = client.request(method, path, **kw)
        assert r.status_code == 401, (method, path, r.status_code)
        r = client.request(method, path, headers=auth(fresh), **kw)
        if (method, path) in _ANY_STAFF:
            assert r.status_code != 403, (method, path)
        else:
            assert r.status_code == 403, (method, path, r.status_code)
            assert r.json()["detail"] == "비밀번호를 먼저 변경해야 합니다", (method, path)
        if _is_admin_only(method, path):
            r = client.request(method, path, headers=auth(nurse), **kw)
            assert r.status_code == 403, (method, path, r.status_code)
            assert r.json()["detail"] == "관리자만 사용할 수 있습니다", (method, path)


def test_admitted_on_uses_kst_date(tmp_path):
    clock = FakeClock(datetime(2026, 11, 10, 15, 30, tzinfo=UTC))
    application = create_app(Config(db_path=tmp_path / "k.db"), clock)
    try:
        from fastapi.testclient import TestClient

        with TestClient(application) as c:
            _, tok = make_staff(c, application, Role.ADMIN, "kadmin")
            r = c.post("/patients", json=P, headers=auth(tok))
            assert r.status_code == 201
            assert r.json()["admitted_on"] == "2026-11-11"
    finally:
        application.state.engine.dispose()
