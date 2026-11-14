from conftest import _patient, auth
from milkbank import packs
from milkbank.models import Pack, RecordKind, StockRecord
from sqlalchemy import select


def _ok(client, token, pid, **body):
    r = client.post(f"/patients/{pid}/records", json=body, headers=auth(token))
    assert r.status_code == 201, r.text
    return r.json()


def _discharge(client, token, pid, remaining="return"):
    r = client.post(
        f"/patients/{pid}/discharge", json={"remaining": remaining}, headers=auth(token)
    )
    assert r.status_code == 200, r.text


def test_discharge_takes_all_packs(client, app, admin_token, nurse_token):
    pid = _patient(client, admin_token, "D-1")
    _ok(client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, None, 100])
    _discharge(client, admin_token, pid)
    with app.state.session_factory() as db:
        assert packs.in_stock(db, pid) == []
        rec = db.scalars(
            select(StockRecord).where(
                StockRecord.patient_id == pid, StockRecord.kind == RecordKind.RETURN
            )
        ).one()
        assert (rec.count, rec.ml, rec.ml_unknown) == (3, 180, 1)
        out = db.scalars(select(Pack).where(Pack.patient_id == pid)).all()
        assert all(p.out_record_id == rec.id for p in out)


def test_discharge_150_packs_one_record(client, app, admin_token, nurse_token):
    pid = _patient(client, admin_token, "D-2")
    _ok(client, nurse_token, pid, kind="intake", count=100, pack_mls=[50] * 100)
    _ok(client, nurse_token, pid, kind="intake", count=50, pack_mls=[60] * 50)
    r = client.post(
        f"/patients/{pid}/discharge",
        json={"remaining": "discard", "discard_reason": "contaminated"},
        headers=auth(admin_token),
    )
    assert r.status_code == 200, r.text
    with app.state.session_factory() as db:
        recs = db.scalars(
            select(StockRecord).where(
                StockRecord.patient_id == pid, StockRecord.kind == RecordKind.DISCARD
            )
        ).all()
        assert len(recs) == 1
        assert (recs[0].count, recs[0].ml, recs[0].ml_unknown) == (150, 8000, 0)
        assert packs.in_stock(db, pid) == []


def test_patient_list_balance_ml(client, admin_token, nurse_token):
    a = _patient(client, admin_token, "L-1")
    _ok(client, nurse_token, a, kind="intake", count=3, pack_mls=[80, 80, None])
    rows = client.get("/patients", headers=auth(nurse_token)).json()
    row = next(r for r in rows if r["id"] == a)
    assert row["balance"] == 3
    assert row["balance_ml"] == 160 and row["unknown_ml_packs"] == 1


def test_overview_balance_ml(client, admin_token, nurse_token):
    a = _patient(client, admin_token, "O-1")
    _ok(client, nurse_token, a, kind="intake", count=3, pack_mls=[80, 80, None])
    _ok(client, nurse_token, a, kind="use", count=1)  # 먼저 들어온 80mL 팩
    row = client.get("/admin/overview", headers=auth(admin_token)).json()["rows"][0]
    assert row["balance"] == 2
    assert row["balance_ml"] == 80 and row["unknown_ml_packs"] == 1


def test_daily_uses_record_ml(client, admin_token, nurse_token):
    a = _patient(client, admin_token, "Y-1")
    _ok(client, nurse_token, a, kind="intake", count=2, pack_mls=[80, 100])
    _ok(client, nurse_token, a, kind="use", count=1)
    d = client.get("/admin/daily", headers=auth(admin_token)).json()
    assert d["rows"][0]["use_ml"] == 80 and d["total"]["use_ml"] == 80
    assert d["total"]["in_ml"] == 180


def test_erase_keeps_packs(client, app, admin_token, nurse_token):
    pid = _patient(client, admin_token, "E-1")
    _ok(client, nurse_token, pid, kind="intake", count=2, pack_mls=[80, None])
    _discharge(client, admin_token, pid)
    with app.state.session_factory() as db:
        before = [(p.id, p.ml, p.out_record_id) for p in db.scalars(select(Pack).where(Pack.patient_id == pid))]
    assert client.post(f"/patients/{pid}/erase", headers=auth(admin_token)).status_code == 200
    with app.state.session_factory() as db:
        after = [(p.id, p.ml, p.out_record_id) for p in db.scalars(select(Pack).where(Pack.patient_id == pid))]
    assert before == after and len(after) == 2
