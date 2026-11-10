from milkbank.models import Role

from conftest import auth, make_staff


def _create(client, token, username="n2", role="nurse"):
    return client.post(
        "/staff",
        json={"username": username, "name": "박간호", "role": role},
        headers=auth(token),
    )


def test_admin_creates_nurse(client, admin_token):
    r = _create(client, admin_token)
    assert r.status_code == 201
    assert r.json()["staff"]["must_change_password"] is True
    assert len(r.json()["temp_password"]) == 10


def test_list_staff_sorted_by_username(client, admin_token):
    _create(client, admin_token, "b2")
    _create(client, admin_token, "a1")
    r = client.get("/staff", headers=auth(admin_token))
    assert r.status_code == 200
    names = [s["username"] for s in r.json()]
    assert names == sorted(names)
    assert {"a1", "b2", "admin1"} <= set(names)


def test_duplicate_username(client, admin_token):
    assert _create(client, admin_token).status_code == 201
    r = _create(client, admin_token)
    assert r.status_code == 409
    assert r.json()["detail"] == "이미 사용 중인 아이디입니다"


def test_nurse_cannot_manage_staff(client, nurse_token):
    calls = [
        lambda: client.get("/staff", headers=auth(nurse_token)),
        lambda: _create(client, nurse_token),
        lambda: client.patch("/staff/1", json={"active": False}, headers=auth(nurse_token)),
        lambda: client.post("/staff/1/reset-password", headers=auth(nurse_token)),
    ]
    for call in calls:
        r = call()
        assert r.status_code == 403
        assert r.json()["detail"] == "관리자만 사용할 수 있습니다"


def test_deactivate_kills_sessions(client, admin_token, app):
    nid, ntok = make_staff(client, app, Role.NURSE, "n3")
    r = client.patch(f"/staff/{nid}", json={"active": False}, headers=auth(admin_token))
    assert r.status_code == 200
    assert r.json()["active"] is False
    assert client.get("/auth/me", headers=auth(ntok)).status_code == 401
    r = client.post("/auth/login", json={"username": "n3", "password": "newpass123"})
    assert r.status_code == 401
    assert r.json()["detail"] == "아이디 또는 비밀번호가 올바르지 않습니다"


def test_reactivate(client, admin_token, app):
    nid, _ = make_staff(client, app, Role.NURSE, "n4")
    client.patch(f"/staff/{nid}", json={"active": False}, headers=auth(admin_token))
    r = client.patch(f"/staff/{nid}", json={"active": True}, headers=auth(admin_token))
    assert r.status_code == 200 and r.json()["active"] is True
    r = client.post("/auth/login", json={"username": "n4", "password": "newpass123"})
    assert r.status_code == 200


def test_reset_password_kills_sessions(client, admin_token, app):
    nid, ntok = make_staff(client, app, Role.NURSE, "n5")
    r = client.post(f"/staff/{nid}/reset-password", headers=auth(admin_token))
    assert r.status_code == 200
    temp = r.json()["temp_password"]
    assert client.get("/auth/me", headers=auth(ntok)).status_code == 401
    r = client.post("/auth/login", json={"username": "n5", "password": temp})
    assert r.status_code == 200
    assert r.json()["must_change_password"] is True


def test_cannot_deactivate_self(client, app):
    aid, tok = make_staff(client, app, Role.ADMIN, "admin2")
    r = client.patch(f"/staff/{aid}", json={"active": False}, headers=auth(tok))
    assert r.status_code == 400
    assert r.json()["detail"] == "본인 계정은 비활성화할 수 없습니다"


def test_reset_unknown_staff(client, admin_token):
    r = client.post("/staff/9999/reset-password", headers=auth(admin_token))
    assert r.status_code == 404
    assert r.json()["detail"] == "직원을 찾을 수 없습니다"


def test_patch_unknown_staff(client, admin_token):
    r = client.patch("/staff/9999", json={"active": False}, headers=auth(admin_token))
    assert r.status_code == 404
