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


def _inputs_with_uses(client, app, clock, admin_token, uses):
    """uses: [(UTC 시각, 팩 수, 취소 여부)]로 USE 기록을 넣고 judge_inputs(avg_days=3)를 돌려준다."""
    pid = _patient(client, admin_token, "E-1")
    with app.state.session_factory() as db:
        staff_id = db.query(Staff).first().id
        for at, count, canceled in uses:
            rec = StockRecord(
                patient_id=pid, kind=RecordKind.USE, count=count, staff_id=staff_id,
                created_at=at,
            )
            db.add(rec)
            db.flush()
            if canceled:
                db.add(
                    StockRecord(
                        patient_id=pid, kind=RecordKind.CANCEL, count=count,
                        staff_id=staff_id, created_at=at, target_id=rec.id,
                    )
                )
        db.commit()
        return reports.judge_inputs(db, pid, reports.kst_today(clock), 3)


def test_judge_inputs_window_edges_kst(client, app, clock, admin_token):
    from datetime import UTC, date, datetime

    def u(d, h, m):
        return datetime(2026, 11, d, h, m, tzinfo=UTC)

    # 오늘(KST)은 11-10. 창은 KST [11-07 00:00, 11-10 00:00) = UTC [11-06 15:00, 11-09 15:00)
    first, recent = _inputs_with_uses(
        client, app, clock, admin_token,
        [
            (u(6, 14, 59), 8, False),   # 창 시작 1분 전 -> 제외
            (u(6, 15, 0), 2, False),    # 창 시작 -> 포함
            (u(9, 14, 59), 4, False),   # 창 끝 1분 전 -> 포함
            (u(9, 15, 0), 16, False),   # KST 오늘 00:00 -> 제외
            (u(8, 0, 0), 32, True),     # 창 안이지만 취소됨 -> 제외
        ],
    )
    assert recent == 6
    assert first == date(2026, 11, 6)  # 첫 사용은 KST 11-06 23:59 (UTC 날짜도 같음)


def test_judge_inputs_first_day_uses_kst_date(client, app, clock, admin_token):
    from datetime import UTC, date, datetime

    # UTC 11-08 15:30 = KST 11-09 00:30
    first, _ = _inputs_with_uses(
        client, app, clock, admin_token, [(datetime(2026, 11, 8, 15, 30, tzinfo=UTC), 1, False)]
    )
    assert first == date(2026, 11, 9)


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


def _set_bed(app, pid, bed):
    from milkbank.models import Patient

    with app.state.session_factory() as db:
        db.get(Patient, pid).bed = bed
        db.commit()


def _use_history(app, clock, pid, intake, uses):
    """intake 팩을 6일 전에 넣고 uses=[(며칠 전, 팩 수)]의 사용 기록을 넣는다."""
    with app.state.session_factory() as db:
        staff_id = db.query(Staff).first().id
        db.add(StockRecord(
            patient_id=pid, kind=RecordKind.INTAKE, count=intake, staff_id=staff_id,
            created_at=clock.now() - timedelta(days=6),
        ))
        for days_ago, n in uses:
            db.add(StockRecord(
                patient_id=pid, kind=RecordKind.USE, count=n, staff_id=staff_id,
                created_at=clock.now() - timedelta(days=days_ago),
            ))
        db.commit()


def test_daily_avg_divisor_is_avg_days(client, app, clock, admin_token):
    # 첫 사용은 5일 전, 최근 3일(오늘 제외) 사용 6팩 -> 6 / avg_days(3) = 2.0
    pid = _patient(client, admin_token, "D-1")
    _use_history(app, clock, pid, 20, [(5, 1), (2, 6)])
    row = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"][0]
    assert row["daily_avg"] == 2.0
    assert row["balance"] == 13


def test_patient_days_threshold_override(client, app, clock, admin_token):
    # 잔량 5 / 일평균 2.0 = 2.5일: 기본 기준(2일)이면 충분, 환자 기준 3일이면 부족
    pid = _patient(client, admin_token, "D-2")
    _use_history(app, clock, pid, 12, [(5, 1), (2, 6)])
    row = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"][0]
    assert (row["days_left"], row["status"]) == (2.5, "enough")
    r = client.patch(f"/patients/{pid}", json={"days_threshold": 3}, headers=auth(admin_token))
    assert r.status_code == 200, r.text
    row = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"][0]
    assert (row["days_left"], row["status"]) == (2.5, "low")


def test_empty_counts_as_low(client, admin_token):
    _patient(client, admin_token, "D-3")  # 재고 0
    body = client.get("/admin/overview", headers=auth(admin_token)).json()
    assert body["rows"][0]["status"] == "empty"
    assert body["low"] == 1


def test_overview_sorted_by_bed_regardless_of_creation(client, app, admin_token):
    ids = {bed: _patient(client, admin_token, f"B-{bed}") for bed in ("10", "2", "1")}
    for bed, pid in ids.items():
        _set_bed(app, pid, bed)
    rows = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"]
    assert [r["bed"] for r in rows] == ["1", "2", "10"]
