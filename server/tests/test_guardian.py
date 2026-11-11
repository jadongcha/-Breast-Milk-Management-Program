from conftest import _gauth, _login, auth
from sqlalchemy import select

from milkbank.models import (
    GuardianSession,
    Patient,
    PatientStatus,
    Submission,
    SubmissionStatus,
)

PHONE = "01012345678"
BAD_LOGIN = "전화번호 또는 아기 ID가 올바르지 않습니다"


def test_login_lists_all_admitted_babies_of_phone(client, world):
    r = _login(client, world["a"], "010-1234-5678", " demo-01 ")
    assert r.status_code == 200, r.text
    babies = r.json()["babies"]
    assert [b["baby_id"] for b in babies] == ["DEMO-01", "DEMO-02"]
    assert babies[0]["balance"] == 3 and babies[0]["pending_count"] == 0
    assert babies[1]["balance"] == 0
    assert r.json()["token"]
    r = _login(client, world["a"], " 010 1234 5678 ", "DEMO-02")
    assert r.status_code == 200


def test_pending_count_sums_pending_only(client, app, clock, world):
    with app.state.session_factory() as db:
        for status, count in [
            (SubmissionStatus.PENDING, 2),
            (SubmissionStatus.PENDING, 3),
            (SubmissionStatus.CONFIRMED, 4),
        ]:
            db.add(
                Submission(
                    patient_id=world["p1"], station_id=world["a_id"], count=count,
                    submitted_at=clock.now(), status=status,
                )
            )
        db.commit()
    token = _login(client, world["a"]).json()["token"]
    r = client.get("/guardian/babies", headers=_gauth(world["a"], token))
    assert r.status_code == 200
    assert [b["pending_count"] for b in r.json()] == [5, 0]


def test_login_failure_message_is_generic(client, world):
    r = _login(client, world["a"], "010-9999-8888", "DEMO-01")
    assert r.status_code == 401 and r.json()["detail"] == BAD_LOGIN
    r = _login(client, world["a"], "010-1234-5678", "NOPE")
    assert r.status_code == 401 and r.json()["detail"] == BAD_LOGIN


def test_bad_phone_format_is_400(client, world):
    r = _login(client, world["a"], "abc", "DEMO-01")
    assert r.status_code == 400
    assert r.json()["detail"] == "전화번호 형식이 올바르지 않습니다"


def test_login_needs_station_token(client, world):
    r = client.post("/guardian/login", json={"phone": PHONE, "baby_id": "DEMO-01"})
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"


def test_discharged_baby_cannot_login_and_is_hidden(client, app, world):
    with app.state.session_factory() as db:
        db.get(Patient, world["p2"]).status = PatientStatus.DISCHARGED
        db.commit()
    assert _login(client, world["a"], baby="DEMO-02").status_code == 401
    r = _login(client, world["a"], baby="DEMO-01")
    assert r.status_code == 200
    assert [b["baby_id"] for b in r.json()["babies"]] == ["DEMO-01"]


def test_phone_lock_after_five_failures(client, clock, world):
    for _ in range(5):
        assert _login(client, world["a"], baby="WRONG").status_code == 401
    r = _login(client, world["a"])
    assert r.status_code == 429
    assert r.json()["detail"] == (
        "로그인을 5번 실패해 10분 동안 잠겼습니다. 잠시 후 다시 시도해 주세요"
    )
    clock.advance(minutes=10, seconds=1)
    assert _login(client, world["a"]).status_code == 200


def test_station_lock_after_twenty_failures_across_phones(client, world):
    for i in range(20):
        r = _login(client, world["a"], phone=f"0101111{i:04d}", baby="X")
        assert r.status_code == 401, i
    r = _login(client, world["a"])
    assert r.status_code == 429 and r.json()["detail"] == "잠시 뒤에 다시 시도해 주세요"
    assert _login(client, world["b"]).status_code == 200


def test_station_failures_not_cleared_by_success(client, world):
    for i in range(19):
        assert _login(client, world["a"], phone=f"0101111{i:04d}", baby="X").status_code == 401
    assert _login(client, world["a"]).status_code == 200
    assert _login(client, world["a"], phone="01022223333", baby="X").status_code == 401
    assert _login(client, world["a"]).status_code == 429


def test_bad_phone_format_counts_for_station(client, world):
    for _ in range(20):
        assert _login(client, world["a"], phone="abc").status_code == 400
    assert _login(client, world["a"]).status_code == 429


def test_session_idle_boundary(client, clock, world):
    st = world["a"]
    token = _login(client, st).json()["token"]
    clock.advance(seconds=60)
    assert client.get("/guardian/babies", headers=_gauth(st, token)).status_code == 200
    clock.advance(seconds=60)
    assert client.get("/guardian/babies", headers=_gauth(st, token)).status_code == 200
    clock.advance(seconds=61)
    r = client.get("/guardian/babies", headers=_gauth(st, token))
    assert r.status_code == 401 and r.json()["detail"] == "로그인이 필요합니다"
    r = client.get("/guardian/babies", headers=_gauth(st, token))
    assert r.status_code == 401


def test_expired_session_row_is_deleted(client, app, clock, world):
    st = world["a"]
    token = _login(client, st).json()["token"]
    clock.advance(seconds=61)
    assert client.get("/guardian/babies", headers=_gauth(st, token)).status_code == 401
    with app.state.session_factory() as db:
        assert db.scalars(select(GuardianSession)).all() == []


def test_login_purges_expired_sessions_of_that_station_only(client, app, clock, world):
    _login(client, world["a"])
    _login(client, world["b"])
    clock.advance(seconds=61)
    _login(client, world["a"])
    with app.state.session_factory() as db:
        rows = db.scalars(select(GuardianSession).order_by(GuardianSession.id)).all()
        # B의 옛 세션은 남고, A의 옛 세션은 지워지며 A의 새 세션이 있다.
        assert [s.station_id for s in rows] == [world["b_id"], world["a_id"]]


def test_tokens_do_not_cross(client, admin_token, world):
    gtoken = _login(client, world["a"]).json()["token"]
    # 직원 토큰으로 보호자 API
    r = client.get("/guardian/babies", headers=_gauth(world["a"], admin_token))
    assert r.status_code == 401
    # 보호자 토큰으로 직원 API
    assert client.get("/patients", headers=auth(gtoken)).status_code == 401
    # A의 보호자 토큰 + B의 제출처 토큰
    r = client.get("/guardian/babies", headers=_gauth(world["b"], gtoken))
    assert r.status_code == 401
    # 제출처 토큰 없이
    r = client.get("/guardian/babies", headers=auth(gtoken))
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"
    # 제출처 토큰만 있고 보호자 토큰 없음
    assert client.get("/guardian/babies", headers=world["a"]).status_code == 401
    # 원래 쌍은 여전히 유효
    assert client.get("/guardian/babies", headers=_gauth(world["a"], gtoken)).status_code == 200


def test_deactivating_station_ends_guardian_session(client, admin_token, world):
    token = _login(client, world["a"]).json()["token"]
    r = client.patch(
        f"/stations/{world['a_id']}", json={"active": False}, headers=auth(admin_token)
    )
    assert r.status_code == 200
    r = client.get("/guardian/babies", headers=_gauth(world["a"], token))
    assert r.status_code == 401 and r.json()["code"] == "station_invalid"


def test_logout_ends_session(client, world):
    st = world["a"]
    token = _login(client, st).json()["token"]
    assert client.post("/guardian/logout", headers=_gauth(st, token)).status_code == 204
    assert client.get("/guardian/babies", headers=_gauth(st, token)).status_code == 401
