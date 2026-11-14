from datetime import UTC, date, datetime, timedelta

from sqlalchemy import select

from conftest import _gauth, _login, auth
from milkbank.clock import FakeClock
from milkbank.config import Config
from milkbank.main import create_app
from milkbank import discharge as discharge_service
from milkbank.models import (
    DiscardReason, Patient, PatientStatus, RecordKind, Staff, StockRecord, Submission, SubmissionStatus,
)


def _intake(client, token, pid, count):
    r = client.post(
        f"/patients/{pid}/records", json={"kind": "intake", "count": count},
        headers=auth(token),
    )
    assert r.status_code == 201, r.text


def _discharge(client, token, pid, **body):
    body.setdefault("remaining", "return")
    return client.post(f"/patients/{pid}/discharge", json=body, headers=auth(token))


def _records(app, pid):
    with app.state.session_factory() as db:
        return list(db.scalars(select(StockRecord).where(StockRecord.patient_id == pid)))


def test_discharge_return(client, app, admin_token, world):
    p = world["p1"]
    client.post(f"/patients/{p}/records", json={"kind": "use", "count": 1}, headers=auth(admin_token))
    r = _discharge(client, admin_token, p)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "discharged"
    assert body["discharged_on"] == "2026-11-10"
    assert body["balance"] == 0
    rets = [x for x in _records(app, p) if x.kind == RecordKind.RETURN]
    assert [x.count for x in rets] == [2]
    r = client.post(f"/patients/{p}/records", json={"kind": "intake", "count": 1}, headers=auth(admin_token))
    assert r.status_code == 409 and r.json()["detail"] == "퇴원한 환자입니다"
    assert _login(client, world["a"]).status_code == 401


def test_discharge_discard_needs_reason(client, app, admin_token, world):
    p = world["p1"]
    r = _discharge(client, admin_token, p, remaining="discard")
    assert r.status_code == 400 and r.json()["detail"] == "폐기 사유를 선택해 주세요"
    assert all(x.kind == RecordKind.INTAKE for x in _records(app, p))
    got = client.get(f"/patients/{p}", headers=auth(admin_token)).json()
    assert got["status"] == "admitted" and got["balance"] == 3
    r = _discharge(client, admin_token, p, remaining="discard", discard_reason="contaminated")
    assert r.status_code == 200, r.text
    rec = [x for x in _records(app, p) if x.kind == RecordKind.DISCARD]
    assert [(x.count, x.discard_reason) for x in rec] == [(3, DiscardReason.CONTAMINATED)]


def test_discharge_zero_balance(client, app, admin_token, world):
    p = world["p2"]
    r = _discharge(client, admin_token, p, remaining="discard")
    assert r.status_code == 200, r.text
    assert _records(app, p) == []


def test_discharge_twice(client, admin_token, world):
    p = world["p1"]
    assert _discharge(client, admin_token, p).status_code == 200
    r = _discharge(client, admin_token, p)
    assert r.status_code == 409 and r.json()["detail"] == "이미 퇴원한 환자입니다"


def test_discharge_ends_guardian_session(client, admin_token, world):
    tok = _login(client, world["a"]).json()["token"]
    h = _gauth(world["a"], tok)
    assert client.get("/guardian/babies", headers=h).status_code == 200
    # 같은 전화번호의 다른 아기도 퇴원시켜 전화번호의 입원 아기가 없게 한다.
    assert _discharge(client, admin_token, world["p1"]).status_code == 200
    assert _discharge(client, admin_token, world["p2"]).status_code == 200
    assert client.get("/guardian/babies", headers=h).status_code == 401


def test_discharge_keeps_session_for_twin(client, admin_token, world):
    tok = _login(client, world["a"]).json()["token"]
    h = _gauth(world["a"], tok)
    assert _discharge(client, admin_token, world["p1"]).status_code == 200
    r = client.get("/guardian/babies", headers=h)
    assert r.status_code == 200, r.text
    babies = r.json()
    babies = babies.get("babies", babies) if isinstance(babies, dict) else babies
    assert [b["baby_id"] for b in babies] == ["DEMO-02"]


def _discharged(client, app, admin_token, world):
    p = world["p1"]
    with app.state.session_factory() as db:
        staff_id = db.query(Staff).first().id
        rec = db.scalars(select(StockRecord).where(StockRecord.patient_id == p)).first()
        rec.memo = "비밀 메모"
        db.add(Submission(
            patient_id=p, station_id=None, count=1, submitted_at=app.state.clock.now(),
            status=SubmissionStatus.PENDING, memo="제출 메모",
        ))
        db.commit()
        assert staff_id
    assert _discharge(client, admin_token, p).status_code == 200
    return p


def test_erase_now(client, app, admin_token, world):
    p = _discharged(client, app, admin_token, world)
    r = client.post(f"/patients/{p}/erase", headers=auth(admin_token))
    assert r.status_code == 200, r.text
    b = r.json()
    assert (b["baby_name"], b["reg_no"], b["guardian_name"], b["guardian_phone"]) == (
        f"퇴원환자-{p}", f"말소-{p}", "", "",
    )
    assert b["erased_at"] is not None
    recs = _records(app, p)
    assert all(x.memo is None for x in recs)
    assert sorted((x.kind, x.count) for x in recs) == [
        (RecordKind.INTAKE, 3), (RecordKind.RETURN, 3),
    ]
    with app.state.session_factory() as db:
        assert db.scalars(select(Submission.memo).where(Submission.patient_id == p)).all() == [None]


def test_erase_requires_discharged(client, admin_token, world):
    r = client.post(f"/patients/{world['p1']}/erase", headers=auth(admin_token))
    assert r.status_code == 409 and r.json()["detail"] == "퇴원한 환자만 말소할 수 있습니다"


def test_erase_twice(client, app, admin_token, world):
    p = _discharged(client, app, admin_token, world)
    assert client.post(f"/patients/{p}/erase", headers=auth(admin_token)).status_code == 200
    r = client.post(f"/patients/{p}/erase", headers=auth(admin_token))
    assert r.status_code == 409 and r.json()["detail"] == "이미 말소된 환자입니다"


def test_erased_cannot_be_edited(client, app, admin_token, world):
    p = _discharged(client, app, admin_token, world)
    client.post(f"/patients/{p}/erase", headers=auth(admin_token))
    r = client.patch(f"/patients/{p}", json={"bed": "9"}, headers=auth(admin_token))
    assert r.status_code == 409 and r.json()["detail"] == "말소된 환자는 수정할 수 없습니다"


def test_reregister_after_erase(client, app, admin_token, world):
    p = _discharged(client, app, admin_token, world)
    client.post(f"/patients/{p}/erase", headers=auth(admin_token))
    r = client.post(
        "/patients",
        json={"reg_no": "DEMO-01", "baby_name": "새아기", "bed": "3",
              "guardian_name": "박엄마", "guardian_phone": "010-9999-8888"},
        headers=auth(admin_token),
    )
    assert r.status_code == 201, r.text


def test_auto_erase_boundary(client, app, admin_token, world):
    p = world["p1"]
    assert _discharge(client, admin_token, p).status_code == 200
    d = date(2026, 11, 10)
    with app.state.session_factory() as db:
        db.get(Patient, p).discharged_on = d
        db.commit()

    def run(day):
        clock = FakeClock(datetime(day.year, day.month, day.day, 3, 0, tzinfo=UTC))
        with app.state.session_factory() as db:
            ids = discharge_service.erase_due(db, clock)
            db.commit()
            return ids

    assert run(d + timedelta(days=29)) == []
    assert run(d + timedelta(days=30)) == [p]
    assert run(d + timedelta(days=31)) == []


def test_auto_erase_runs_on_startup(client, app, tmp_path, admin_token, world):
    from fastapi.testclient import TestClient

    p = world["p1"]
    assert _discharge(client, admin_token, p).status_code == 200
    later = FakeClock(app.state.clock.now() + timedelta(days=31))
    app2 = create_app(Config(db_path=tmp_path / "t.db"), later)
    try:
        with TestClient(app2):
            with app2.state.session_factory() as db:
                got = db.get(Patient, p)
                assert got.erased_at is not None and got.reg_no == f"말소-{p}"
    finally:
        app2.state.engine.dispose()


def test_erased_patient_still_counted(client, app, admin_token, world):
    p = world["p1"]
    assert _discharge(client, admin_token, p).status_code == 200
    client.post(f"/patients/{p}/erase", headers=auth(admin_token))
    r = client.get("/admin/daily?date=2026-11-10", headers=auth(admin_token))
    assert r.status_code == 200, r.text
    rows = {x["baby_id"]: x for x in r.json()["rows"]}
    assert rows[f"말소-{p}"]["in_packs"] == 3
    assert rows[f"말소-{p}"]["return_packs"] == 3


def test_discharge_erase_admin_only(client, admin_token, nurse_token, world):
    p = world["p1"]
    r = _discharge(client, nurse_token, p)
    assert r.status_code == 403
    r = client.post(f"/patients/{p}/erase", headers=auth(nurse_token))
    assert r.status_code == 403


def test_discharge_over_100_packs(client, app, admin_token, world):
    p = world["p1"]  # 이미 3팩
    _intake(client, admin_token, p, 100)
    _intake(client, admin_token, p, 47)
    r = _discharge(client, admin_token, p)
    assert r.status_code == 200, r.text
    assert r.json()["balance"] == 0
    rets = [x for x in _records(app, p) if x.kind == RecordKind.RETURN]
    assert [x.count for x in rets] == [150]


def test_reg_no_erased_prefix_rejected(client, admin_token, world):
    msg = "‘말소-’로 시작하는 등록번호는 쓸 수 없습니다"
    body = {"reg_no": " 말소-7", "baby_name": "a", "bed": "3",
            "guardian_name": "g", "guardian_phone": "010-1111-2222"}
    r = client.post("/patients", json=body, headers=auth(admin_token))
    assert r.status_code == 400 and r.json()["detail"] == msg
    r = client.patch(f"/patients/{world['p1']}", json={"reg_no": "말소-7"}, headers=auth(admin_token))
    assert r.status_code == 400 and r.json()["detail"] == msg


def test_erase_due_skips_failing_patient(client, app, admin_token, world):
    p1, p2 = world["p1"], world["p2"]
    assert _discharge(client, admin_token, p1).status_code == 200
    assert _discharge(client, admin_token, p2).status_code == 200
    with app.state.session_factory() as db:
        for pid in (p1, p2):
            db.get(Patient, pid).discharged_on = date(2026, 1, 1)
        db.add(Patient(
            reg_no=f"말소-{p1}", baby_name="x", bed="9", guardian_name="g",
            guardian_phone="01000000000", status=PatientStatus.ADMITTED,
            admitted_on=date(2026, 1, 1),
            created_at=app.state.clock.now(),
        ))
        db.commit()
    with app.state.session_factory() as db:
        ids = discharge_service.erase_due(db, app.state.clock)
        db.commit()
    assert ids == [p2]
    with app.state.session_factory() as db:
        assert db.get(Patient, p1).erased_at is None
        assert db.get(Patient, p2).erased_at is not None
