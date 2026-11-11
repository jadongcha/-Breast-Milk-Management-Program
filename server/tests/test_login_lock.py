from conftest import _login as guardian_login
from conftest import auth, make_staff

from milkbank.models import LoginAttempt, Role

LOCKED = "로그인을 5번 실패해 10분 동안 잠겼습니다. 잠시 후 다시 시도해 주세요"
BAD = "아이디 또는 비밀번호가 올바르지 않습니다"
GOOD = "newpass123"


def _login(client, username, password):
    return client.post("/auth/login", json={"username": username, "password": password})


def _fail(client, username, n=1):
    for _ in range(n):
        r = _login(client, username, "wrong-pass")
        assert r.status_code == 401, r.text
        assert r.json()["detail"] == BAD


def _nurse(client, app, username="nurse9"):
    make_staff(client, app, Role.NURSE, username)
    return username


def test_lock_after_five_failures(client, app):
    u = _nurse(client, app)
    _fail(client, u, 5)
    r = _login(client, u, GOOD)
    assert r.status_code == 429
    assert r.json()["detail"] == LOCKED


def test_lock_persists_across_http_requests(client, app):
    u = _nurse(client, app)
    _fail(client, u, 5)
    with app.state.session_factory() as db:
        attempt = db.get(LoginAttempt, u)
        assert attempt is not None
        assert attempt.locked_until is not None


def test_failure_count_persisted_after_error(client, app):
    u = _nurse(client, app)
    _fail(client, u, 2)
    with app.state.session_factory() as db:
        assert db.get(LoginAttempt, u).failed_count == 2


def test_lock_boundary(client, app, clock):
    u = _nurse(client, app)
    _fail(client, u, 5)
    clock.advance(minutes=9, seconds=59)
    assert _login(client, u, GOOD).status_code == 429
    clock.advance(seconds=1)
    assert _login(client, u, GOOD).status_code == 200


def test_success_resets_count(client, app):
    u = _nurse(client, app)
    _fail(client, u, 4)
    assert _login(client, u, GOOD).status_code == 200
    _fail(client, u, 4)
    assert _login(client, u, GOOD).status_code == 200


def test_unknown_username_also_locks(client):
    _fail(client, "ghost", 5)
    r = _login(client, "ghost", "whatever123")
    assert r.status_code == 429
    assert r.json()["detail"] == LOCKED


def test_inactive_account_counts(client, app, admin_token):
    staff_id, _ = make_staff(client, app, Role.NURSE, "nurse8")
    r = client.patch(
        f"/staff/{staff_id}", json={"active": False}, headers=auth(admin_token)
    )
    assert r.status_code == 200, r.text
    for _ in range(5):
        assert _login(client, "nurse8", GOOD).status_code == 401
    assert _login(client, "nurse8", GOOD).status_code == 429


def test_lock_counts_normalized_username(client, app):
    _nurse(client, app, "nurse9")
    for name in ["Nurse9", " nurse9 ", "NURSE9", "nurse9", "Nurse9"]:
        assert _login(client, name, "wrong-pass").status_code == 401
    assert _login(client, "nurse9", GOOD).status_code == 429


def test_reset_password_unlocks(client, app, admin_token):
    staff_id, _ = make_staff(client, app, Role.NURSE, "nurse9")
    _fail(client, "nurse9", 5)
    assert _login(client, "nurse9", GOOD).status_code == 429
    r = client.post(f"/staff/{staff_id}/reset-password", headers=auth(admin_token))
    assert r.status_code == 200, r.text
    temp = r.json()["temp_password"]
    assert _login(client, "nurse9", temp).status_code == 200


def test_locked_does_not_extend(client, app, clock):
    u = _nurse(client, app)
    _fail(client, u, 5)
    clock.advance(minutes=9)
    assert _login(client, u, GOOD).status_code == 429
    clock.advance(minutes=1)
    assert _login(client, u, GOOD).status_code == 200


def test_expired_lock_starts_fresh_count(client, app, clock):
    u = _nurse(client, app)
    _fail(client, u, 5)
    clock.advance(minutes=10)
    _fail(client, u, 1)
    with app.state.session_factory() as db:
        attempt = db.get(LoginAttempt, u)
        assert attempt.failed_count == 1
        assert attempt.locked_until is None


def test_overlong_username_login_writes_nothing(client, app):
    r = _login(client, "x" * 65, "whatever123")
    assert r.status_code == 401
    assert r.json()["detail"] == BAD
    with app.state.session_factory() as db:
        assert db.query(LoginAttempt).count() == 0


def test_max_length_username_login_is_recorded(client, app, clock):
    name = "y" * 64
    assert _login(client, name, "whatever123").status_code == 401
    with app.state.session_factory() as db:
        attempt = db.get(LoginAttempt, name)
        assert attempt is not None and attempt.failed_count == 1
        assert attempt.last_failed_at == clock.now()


def test_overlong_password_is_401_and_counted(client, app):
    u = _nurse(client, app)
    r = _login(client, u, "p" * 257)
    assert r.status_code == 401
    assert r.json()["detail"] == BAD
    with app.state.session_factory() as db:
        assert db.get(LoginAttempt, u).failed_count == 1


def test_create_staff_rejects_overlong_username(db, clock):
    import pytest

    from milkbank import staff as staff_service
    from milkbank.errors import BadRequest

    with pytest.raises(BadRequest) as e:
        staff_service.create_staff(db, clock, username="z" * 65, name="n", role=Role.NURSE)
    assert e.value.message == "아이디는 64자 이하로 입력해 주세요"
    staff_service.create_staff(db, clock, username="z" * 64, name="n", role=Role.NURSE)


def test_colon_username_cannot_lock_station(client, app, world):
    sid = world["a_id"]
    for _ in range(6):
        _fail(client, f"station:{sid}")
    assert guardian_login(client, world["a"]).status_code == 200


def test_colon_username_cannot_lock_guardian_phone(client, app, world):
    for _ in range(6):
        _fail(client, "guardian:01012345678")
    assert guardian_login(client, world["a"]).status_code == 200


def test_colon_username_records_nothing(client, app):
    _fail(client, "station:1", 2)
    _fail(client, "guardian:01012345678", 2)
    with app.state.session_factory() as db:
        assert db.query(LoginAttempt).count() == 0


def test_create_staff_rejects_colon(client, admin_token):
    r = client.post(
        "/staff", json={"username": "a:b", "name": "x", "role": "nurse"},
        headers=auth(admin_token),
    )
    assert r.status_code == 400, r.text
    assert r.json()["detail"] == "아이디에 ':'는 쓸 수 없습니다"
