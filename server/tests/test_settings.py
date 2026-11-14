from conftest import auth, make_staff
from milkbank.models import Role

DEFAULTS = {
    "days_threshold": 2,
    "packs_threshold": 3,
    "avg_days": 3,
    "staff_idle_minutes": 10,
    "retention_days": 30,
}


def test_settings_defaults(client, admin_token):
    r = client.get("/admin/settings", headers=auth(admin_token))
    assert r.status_code == 200
    assert r.json() == DEFAULTS


def test_settings_patch_partial(client, admin_token):
    r = client.patch("/admin/settings", json={"avg_days": 5}, headers=auth(admin_token))
    assert r.status_code == 200
    assert r.json() == {**DEFAULTS, "avg_days": 5}
    r = client.get("/admin/settings", headers=auth(admin_token))
    assert r.json() == {**DEFAULTS, "avg_days": 5}


def test_settings_range(client, admin_token):
    r = client.patch("/admin/settings", json={"retention_days": 0}, headers=auth(admin_token))
    assert r.status_code == 400
    assert r.json()["detail"] == "개인정보 보관 기간은(는) 1~365 사이여야 합니다"
    r = client.patch(
        "/admin/settings",
        json={"avg_days": 4, "days_threshold": 31},
        headers=auth(admin_token),
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "남은 일수 기준은(는) 1~30 사이여야 합니다"
    r = client.get("/admin/settings", headers=auth(admin_token))
    assert r.json() == DEFAULTS


def test_settings_admin_only(client, nurse_token):
    assert client.get("/admin/settings", headers=auth(nurse_token)).status_code == 403
    r = client.patch("/admin/settings", json={"avg_days": 5}, headers=auth(nurse_token))
    assert r.status_code == 403


def test_staff_idle_from_settings(client, app, clock, admin_token):
    _, nurse = make_staff(client, app, Role.NURSE, "nurse-idle")
    r = client.patch(
        "/admin/settings", json={"staff_idle_minutes": 2}, headers=auth(admin_token)
    )
    assert r.status_code == 200
    clock.advance(minutes=1, seconds=59)
    assert client.get("/auth/me", headers=auth(nurse)).status_code == 200
    clock.advance(minutes=2, seconds=1)
    r = client.get("/auth/me", headers=auth(nurse))
    assert r.status_code == 401
    assert r.json()["detail"] == "세션이 만료되었습니다. 다시 로그인해 주세요"


def test_login_and_me_expose_idle(client, app):
    with app.state.session_factory() as db:
        from milkbank import staff as staff_service

        _, temp = staff_service.create_staff(
            db, app.state.clock, username="n9", name="n9", role=Role.NURSE
        )
        db.commit()
    r = client.post("/auth/login", json={"username": "n9", "password": temp})
    assert r.json()["staff_idle_minutes"] == 10
    r = client.get("/auth/me", headers=auth(r.json()["token"]))
    assert r.json()["staff_idle_minutes"] == 10
