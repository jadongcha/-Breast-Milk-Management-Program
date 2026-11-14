from datetime import timedelta

from conftest import _patient, auth
from milkbank import reports
from milkbank.models import (
    PatientStatus,
    RecordKind,
    Staff,
    StockRecord,
    Submission,
    SubmissionStatus,
)


def _rec(client, token, pid, **body):
    r = client.post(f"/patients/{pid}/records", json=body, headers=auth(token))
    assert r.status_code == 201, r.text
    return r.json()["record"]["id"]


def test_judge_inputs_skip_canceled(client, app, clock, admin_token):
    pid = _patient(client, admin_token, "J-1")
    now = clock.now()
    with app.state.session_factory() as db:
        staff_id = db.query(Staff).first().id

        def add(kind, at, target_id=None):
            rec = StockRecord(
                patient_id=pid, kind=kind, count=1, staff_id=staff_id,
                created_at=at, target_id=target_id,
            )
            db.add(rec)
            db.flush()
            return rec.id

        add(RecordKind.INTAKE, now - timedelta(days=5))
        canceled = add(RecordKind.USE, now - timedelta(days=4))
        add(RecordKind.CANCEL, now - timedelta(days=4), target_id=canceled)
        add(RecordKind.USE, now - timedelta(days=2))
        add(RecordKind.USE, now)  # 오늘 사용은 최근 합에서 제외
        db.commit()
        today = reports.kst_today(clock)
        first, recent = reports.judge_inputs(db, pid, today, 3)
    assert first == today - timedelta(days=2)
    assert recent == 1


def test_overview_rows_and_summary(client, app, clock, admin_token):
    low = _patient(client, admin_token, "O-2")
    ok = _patient(client, admin_token, "O-1")
    gone = _patient(client, admin_token, "O-3")
    with app.state.session_factory() as db:
        from milkbank.models import Patient

        db.get(Patient, low).bed = "2"
        db.get(Patient, ok).bed = "10"
        db.get(Patient, gone).status = PatientStatus.DISCHARGED
        db.commit()
    _rec(client, admin_token, low, kind="intake", count=2, ml=160)
    _rec(client, admin_token, ok, kind="intake", count=1, ml=80)
    _rec(client, admin_token, ok, kind="intake", count=7)
    _rec(client, admin_token, ok, kind="intake", count=2)
    _rec(client, admin_token, ok, kind="use", count=1)
    with app.state.session_factory() as db:
        db.add(
            Submission(
                patient_id=low, station_id=None, count=4, submitted_at=clock.now(),
                status=SubmissionStatus.PENDING,
            )
        )
        db.commit()
    r = client.get("/admin/overview", headers=auth(admin_token))
    assert r.status_code == 200, r.text
    body = r.json()
    assert [row["baby_id"] for row in body["rows"]] == ["O-2", "O-1"]
    assert body["admitted"] == 2
    assert body["low"] == 1
    assert body["today_in_packs"] == 12
    assert body["today_in_ml"] == 240
    assert body["today_use_packs"] == 1
    assert body["today_use_ml"] is None
    assert body["pending_submissions"] == 1
    assert body["rows"][0]["pending_packs"] == 4
    assert body["rows"][0]["status"] == "low"
    assert body["rows"][1]["pending_packs"] == 0
    assert body["rows"][1]["balance"] == 9
    assert body["rows"][1]["status"] == "enough"
    assert "아기" not in r.text


def test_patient_threshold_overrides(client, admin_token):
    pid = _patient(client, admin_token, "T-1")
    _rec(client, admin_token, pid, kind="intake", count=2)
    row = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"][0]
    assert row["status"] == "low"
    r = client.patch(f"/patients/{pid}", json={"packs_threshold": 1}, headers=auth(admin_token))
    assert r.status_code == 200, r.text
    row = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"][0]
    assert row["status"] == "enough"


def test_overview_admin_only(client, nurse_token):
    assert client.get("/admin/overview", headers=auth(nurse_token)).status_code == 403
