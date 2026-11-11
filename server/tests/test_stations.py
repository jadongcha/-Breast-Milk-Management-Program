from datetime import timedelta

from conftest import auth

from milkbank import lockout


def _create(client, token, name="1층 로비"):
    return client.post("/stations", json={"name": name}, headers=auth(token))


def test_admin_creates_station_token_once(client, admin_token):
    r = _create(client, admin_token)
    assert r.status_code == 201
    body = r.json()
    assert body["station_token"]
    assert body["station"]["name"] == "1층 로비"
    assert body["station"]["active"] is True
    r = client.get("/stations", headers=auth(admin_token))
    assert r.status_code == 200
    assert "station_token" not in r.text and "token" not in r.text


def test_station_name_rules(client, admin_token):
    assert _create(client, admin_token, "   ").status_code == 400
    assert _create(client, admin_token, "가" * 21).status_code == 400
    sid = _create(client, admin_token, "  외래 ").json()["station"]["id"]
    r = _create(client, admin_token, "외래")
    assert r.status_code == 409
    assert r.json()["detail"] == "이미 있는 제출처 이름입니다"
    r = client.patch(f"/stations/{sid}", json={"active": False}, headers=auth(admin_token))
    assert r.status_code == 200 and r.json()["active"] is False
    assert _create(client, admin_token, "외래").status_code == 201


def test_nurse_cannot_manage_stations(client, nurse_token):
    assert _create(client, nurse_token).status_code == 403
    assert client.get("/stations", headers=auth(nurse_token)).status_code == 403
    r = client.patch("/stations/1", json={"active": False}, headers=auth(nurse_token))
    assert r.status_code == 403


def test_station_self_requires_valid_token(client, admin_token):
    expected = {"detail": "등록된 제출처 기기가 아닙니다", "code": "station_invalid"}
    r = client.get("/station/self")
    assert r.status_code == 401 and r.json() == expected
    r = client.get("/station/self", headers={"X-Station-Token": "bogus"})
    assert r.status_code == 401 and r.json() == expected
    made = _create(client, admin_token).json()
    tok = made["station_token"]
    r = client.get("/station/self", headers={"X-Station-Token": tok})
    assert r.status_code == 200
    assert r.json() == {"id": made["station"]["id"], "name": "1층 로비"}
    client.patch(
        f"/stations/{made['station']['id']}", json={"active": False}, headers=auth(admin_token)
    )
    r = client.get("/station/self", headers={"X-Station-Token": tok})
    assert r.status_code == 401 and r.json() == expected


def test_reactivate_rejected(client, admin_token):
    sid = _create(client, admin_token).json()["station"]["id"]
    r = client.patch(f"/stations/{sid}", json={"active": True}, headers=auth(admin_token))
    assert r.status_code == 400
    assert r.json()["detail"] == "사용 중지한 제출처는 다시 켤 수 없습니다. 새로 등록해 주세요"


def test_delete_self_needs_admin_and_station(client, admin_token, nurse_token):
    tok = _create(client, admin_token).json()["station_token"]
    st = {"X-Station-Token": tok}
    r = client.delete("/station/self", headers={**auth(nurse_token), **st})
    assert r.status_code == 403
    r = client.delete("/station/self", headers=auth(admin_token))
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"
    r = client.delete("/station/self", headers={**auth(admin_token), **st})
    assert r.status_code == 204
    assert client.get("/station/self", headers=st).status_code == 401


def test_lockout_window_resets_old_failures(db, clock):
    kw = dict(threshold=3, lock_minutes=10, window_minutes=10)
    now = clock.now()
    lockout.record_failure(db, "station:1", now, **kw)
    lockout.record_failure(db, "station:1", now, **kw)
    later = now + timedelta(minutes=11)
    lockout.record_failure(db, "station:1", later, **kw)
    lockout.ensure_not_locked(db, "station:1", later, "잠김")
    lockout.record_failure(db, "station:1", later, **kw)
    lockout.record_failure(db, "station:1", later, **kw)
    import pytest

    from milkbank.errors import TooManyRequests

    with pytest.raises(TooManyRequests):
        lockout.ensure_not_locked(db, "station:1", later, "잠김")
    lockout.clear(db, "station:1")
    lockout.ensure_not_locked(db, "station:1", later, "잠김")
