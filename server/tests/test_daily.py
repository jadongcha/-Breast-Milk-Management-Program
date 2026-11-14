from datetime import UTC, date, datetime, timedelta

from conftest import auth
from milkbank import reports
from milkbank.models import DiscardReason, RecordKind, Staff, StockRecord


def _mkpatient(client, admin_token, reg_no, bed):
    r = client.post(
        "/patients",
        json={
            "reg_no": reg_no, "baby_name": "비밀이름", "bed": bed,
            "guardian_name": "김엄마", "guardian_phone": "010-1234-5678",
        },
        headers=auth(admin_token),
    )
    assert r.status_code == 201, r.text
    return r.json()["id"]


def _add(app, pid, kind, at, count=1, ml=None, reason=None, canceled=False):
    with app.state.session_factory() as db:
        staff_id = db.query(Staff).first().id
        rec = StockRecord(
            patient_id=pid, kind=kind, count=count, ml=ml, staff_id=staff_id,
            created_at=at, discard_reason=reason,
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


def _utc(d, h, m=0):
    return datetime(2026, 11, d, h, m, tzinfo=UTC)


def test_daily_rows_and_total(client, app, admin_token):
    a = _mkpatient(client, admin_token, "A-1", "2")
    b = _mkpatient(client, admin_token, "B-1", "1")
    t = _utc(10, 1)  # KST 11-10 10:00
    _add(app, a, RecordKind.INTAKE, t, count=3, ml=240)
    _add(app, a, RecordKind.USE, t, count=1)
    _add(app, a, RecordKind.DISCARD, t, count=1, reason=DiscardReason.CONTAMINATED)
    _add(app, b, RecordKind.USE, t, count=2, ml=100)
    r = client.get("/admin/daily?date=2026-11-10", headers=auth(admin_token))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["date"] == "2026-11-10"
    assert [x["baby_id"] for x in body["rows"]] == ["B-1", "A-1"]  # 병상 순
    assert "day" not in body["rows"][0]
    assert "비밀이름" not in r.text
    row_a = body["rows"][1]
    assert (row_a["in_packs"], row_a["in_ml"], row_a["use_packs"], row_a["use_ml"]) == (3, 240, 1, None)
    assert (row_a["discard_packs"], row_a["discard_ml"], row_a["return_packs"]) == (1, None, 0)
    assert body["total"] == {
        "in_packs": 3, "in_ml": 240, "use_packs": 3, "use_ml": 100,
        "discard_packs": 1, "discard_ml": None, "return_packs": 0,
    }


def test_daily_default_is_today_kst(client, app, admin_token):
    a = _mkpatient(client, admin_token, "A-1", "1")
    _add(app, a, RecordKind.USE, _utc(10, 0))
    r = client.get("/admin/daily", headers=auth(admin_token))
    assert r.json()["date"] == "2026-11-10"
    assert len(r.json()["rows"]) == 1


def test_daily_skips_canceled(client, app, admin_token):
    a = _mkpatient(client, admin_token, "A-1", "1")
    b = _mkpatient(client, admin_token, "B-1", "2")
    t = _utc(10, 1)
    _add(app, a, RecordKind.USE, t, count=2, canceled=True)
    _add(app, b, RecordKind.USE, t, count=1)
    body = client.get("/admin/daily?date=2026-11-10", headers=auth(admin_token)).json()
    assert [x["baby_id"] for x in body["rows"]] == ["B-1"]
    assert body["total"]["use_packs"] == 1
    # 취소된 기록이 같은 아기의 다른 기록 옆에 있어도 합에 들어가지 않는다
    _add(app, b, RecordKind.USE, t, count=4, canceled=True)
    body = client.get("/admin/daily?date=2026-11-10", headers=auth(admin_token)).json()
    assert body["rows"][0]["use_packs"] == 1


def test_daily_kst_boundary(client, app, admin_token):
    a = _mkpatient(client, admin_token, "A-1", "1")
    _add(app, a, RecordKind.USE, _utc(9, 14, 59))   # KST 11-09 23:59
    _add(app, a, RecordKind.USE, _utc(9, 15, 0), count=2)  # KST 11-10 00:00
    with app.state.session_factory() as db:
        rows = reports.daily_rows(db, date(2026, 11, 9), date(2026, 11, 10))
    assert [(r.day, r.use_packs) for r in rows] == [
        (date(2026, 11, 9), 1), (date(2026, 11, 10), 2),
    ]
    h = auth(admin_token)
    d9 = client.get("/admin/daily?date=2026-11-09", headers=h).json()
    d10 = client.get("/admin/daily?date=2026-11-10", headers=h).json()
    assert d9["total"]["use_packs"] == 1
    assert d10["total"]["use_packs"] == 2


def test_series_fills_zero_days(client, app, admin_token):
    a = _mkpatient(client, admin_token, "A-1", "1")
    b = _mkpatient(client, admin_token, "B-1", "2")
    _add(app, a, RecordKind.USE, _utc(10, 1), count=2, ml=50)   # 오늘
    _add(app, a, RecordKind.INTAKE, _utc(7, 1), count=5)        # 3일 전
    _add(app, b, RecordKind.USE, _utc(7, 1), count=1)
    _add(app, a, RecordKind.USE, _utc(8, 1), count=9, canceled=True)
    h = auth(admin_token)
    r = client.get("/admin/series?days=7", headers=h)
    assert r.status_code == 200, r.text
    pts = r.json()
    assert len(pts) == 7
    assert pts[0]["day"] == "2026-11-04" and pts[-1]["day"] == "2026-11-10"
    by = {p["day"]: p for p in pts}
    assert by["2026-11-10"] == {
        "day": "2026-11-10", "in_packs": 0, "use_packs": 2, "discard_packs": 0, "use_ml": 50,
    }
    assert by["2026-11-07"]["in_packs"] == 5 and by["2026-11-07"]["use_packs"] == 1
    assert by["2026-11-08"] == {
        "day": "2026-11-08", "in_packs": 0, "use_packs": 0, "discard_packs": 0, "use_ml": None,
    }
    only_a = client.get(f"/admin/series?days=7&patient_id={a}", headers=h).json()
    assert {p["day"]: p for p in only_a}["2026-11-07"]["use_packs"] == 0
    assert len(client.get("/admin/series?days=30", headers=h).json()) == 30


def test_series_days_validation(client, admin_token):
    r = client.get("/admin/series?days=10", headers=auth(admin_token))
    assert r.status_code == 400
    assert r.json()["detail"] == "기간은 7일 또는 30일만 고를 수 있습니다"


def test_csv_export(client, app, admin_token):
    a = _mkpatient(client, admin_token, "A-1", "1")
    _add(app, a, RecordKind.INTAKE, _utc(10, 1), count=3, ml=240)
    _add(app, a, RecordKind.USE, _utc(10, 1), count=1)
    r = client.get(
        "/admin/export.csv?from=2026-11-10&to=2026-11-10", headers=auth(admin_token)
    )
    assert r.status_code == 200, r.text
    assert r.headers["content-type"] == "text/csv; charset=utf-8"
    assert r.headers["content-disposition"] == 'attachment; filename="milkbank-2026-11-10-2026-11-10.csv"'
    assert r.content.startswith(b"\xef\xbb\xbf")
    lines = r.content.decode("utf-8-sig").split("\r\n")
    assert lines[0] == "날짜,아기 ID,병상,입고 팩,입고 mL,사용 팩,사용 mL,폐기 팩,폐기 mL,퇴원 반환 팩"
    assert lines[1] == "2026-11-10,A-1,1,3,240,1,,0,,0"
    assert lines[2] == ""
    assert "비밀이름" not in r.text


def test_csv_range_limits(client, admin_token):
    h = auth(admin_token)
    start = date(2026, 8, 1)

    def get(days, frm=None, to=None):
        frm = frm or start
        to = to or frm + timedelta(days=days - 1)
        return client.get(f"/admin/export.csv?from={frm}&to={to}", headers=h)

    assert get(92).status_code == 200
    r = get(93)
    assert r.status_code == 400 and r.json()["detail"] == "기간은 92일 이하로 골라 주세요"
    r = get(1, frm=date(2026, 11, 10), to=date(2026, 11, 9))
    assert r.status_code == 400 and r.json()["detail"] == "시작일이 종료일보다 늦습니다"


def test_reports_admin_only(client, nurse_token):
    h = auth(nurse_token)
    for url in (
        "/admin/daily",
        "/admin/series?days=7",
        "/admin/export.csv?from=2026-11-01&to=2026-11-02",
    ):
        assert client.get(url, headers=h).status_code == 403, url
