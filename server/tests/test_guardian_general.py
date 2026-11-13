"""제출처 토큰 없는 보호자 로그인(일반 병원 기기)."""
from conftest import _gauth, _login, auth
from sqlalchemy import select

from milkbank import guardians as guardian_service
from milkbank.errors import TooManyRequests
from milkbank.models import GuardianSession

LOCKED = "잠시 뒤에 다시 시도해 주세요"


def _general_login(client, phone="010-1234-5678", baby="DEMO-01"):
    return _login(client, {}, phone, baby)


def test_general_login_lookup_submit_logout(client, world, admin_token):
    r = _general_login(client)
    assert r.status_code == 200, r.text
    token = r.json()["token"]
    assert client.get("/guardian/babies", headers=auth(token)).status_code == 200
    r = client.post(
        "/guardian/submissions", json={"patient_id": world["p1"], "count": 3},
        headers=auth(token),
    )
    assert r.status_code == 201, r.text
    r = client.get("/submissions?status=pending", headers=auth(admin_token))
    rows = r.json()
    assert len(rows) == 1 and rows[0]["station_name"] == "병원 기기"
    r = client.post(
        f"/submissions/{rows[0]['id']}/confirm", json={"count": 3}, headers=auth(admin_token)
    )
    assert r.status_code == 200, r.text
    r = client.get(f"/patients/{world["p1"]}", headers=auth(admin_token))
    assert "병원 기기 제출분(제출 3팩)" in r.text
    assert client.post("/guardian/logout", headers=auth(token)).status_code == 204
    r = client.get("/guardian/babies", headers=auth(token))
    assert r.status_code == 401


def test_session_mode_mismatch_is_401(client, world):
    general = _general_login(client).json()["token"]
    station = _login(client, world["a"]).json()["token"]
    r = client.get("/guardian/babies", headers=_gauth(world["a"], general))
    assert r.status_code == 401 and r.json()["detail"] == "로그인이 필요합니다"
    assert "code" not in r.json()
    r = client.get("/guardian/babies", headers=auth(station))
    assert r.status_code == 401 and r.json()["detail"] == "로그인이 필요합니다"
    # 각자의 방식으로는 열린다
    assert client.get("/guardian/babies", headers=auth(general)).status_code == 200
    assert client.get("/guardian/babies", headers=_gauth(world["a"], station)).status_code == 200


def test_guest_ip_lock_after_twenty(client, app, clock, world):
    for i in range(20):
        r = _general_login(client, phone=f"010-2000-{i:04d}", baby="X")
        assert r.status_code == 401
    r = _general_login(client)
    assert r.status_code == 429 and r.json()["detail"] == LOCKED
    # 제출처 토큰을 붙인 로그인은 IP 잠금과 무관하다
    assert _login(client, world["a"]).status_code == 200
    # 다른 IP는 영향이 없다(서비스 함수를 직접 호출)
    cfg = app.state.config
    with app.state.session_factory() as db:
        token, babies = guardian_service.login(
            db, clock, cfg, station=None, client_ip="10.9.9.9",
            phone="010-1234-5678", baby_id="DEMO-01",
        )
        db.commit()
        assert token and babies
        try:
            guardian_service.login(
                db, clock, cfg, station=None, client_ip="testclient",
                phone="010-1234-5678", baby_id="DEMO-01",
            )
            raise AssertionError("locked IP must be refused")
        except TooManyRequests:
            pass
    # 10분이 지나면 풀린다
    clock.advance(minutes=10, seconds=1)
    assert _general_login(client).status_code == 200


def test_station_lock_does_not_lock_general_ip(client, world):
    for i in range(20):
        r = _login(client, world["a"], phone=f"010-3000-{i:04d}", baby="X")
        assert r.status_code == 401
    assert _login(client, world["a"]).status_code == 429
    assert _general_login(client).status_code == 200


def test_general_phone_lock_still_applies(client, world):
    for _ in range(5):
        assert _general_login(client, baby="WRONG").status_code == 401
    r = _general_login(client)
    assert r.status_code == 429 and "잠겼습니다" in r.json()["detail"]


def test_invalid_station_header_still_401_station_invalid(client, world):
    r = _login(client, {"X-Station-Token": "bogus"})
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"
    r = client.get("/guardian/babies", headers={"X-Station-Token": "bogus"})
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"
    r = client.post("/guardian/login", json={"phone": "x", "baby_id": "y"},
                    headers={"X-Station-Token": ""})
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"


def test_general_session_idle_60s(client, app, clock, world):
    token = _general_login(client).json()["token"]
    clock.advance(seconds=61)
    assert client.get("/guardian/babies", headers=auth(token)).status_code == 401
    with app.state.session_factory() as db:
        assert db.scalars(select(GuardianSession)).all() == []


def test_general_session_kept_alive_by_activity(client, clock, world):
    token = _general_login(client).json()["token"]
    clock.advance(seconds=50)
    assert client.get("/guardian/babies", headers=auth(token)).status_code == 200
    clock.advance(seconds=50)
    assert client.get("/guardian/babies", headers=auth(token)).status_code == 200
