import threading

from conftest import _patient, auth
from milkbank import packs, stock
from milkbank.models import Pack, RecordKind, Staff


def _rec(client, token, pid, **body):
    return client.post(f"/patients/{pid}/records", json=body, headers=auth(token))


def _ok(client, token, pid, **body):
    r = _rec(client, token, pid, **body)
    assert r.status_code == 201, r.text
    return r.json()


def _detail(client, token, pid):
    return client.get(f"/patients/{pid}", headers=auth(token)).json()


def _pid(client, admin_token, reg="P-1"):
    return _patient(client, admin_token, reg)


def test_intake_pack_mls(client, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    body = _ok(client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, 80, None])
    assert body["record"]["ml"] == 160
    assert body["record"]["ml_unknown"] == 1
    assert body["balance"] == 3
    assert body["balance_ml"] == 160
    assert body["unknown_ml_packs"] == 1
    d = _detail(client, nurse_token, pid)
    assert d["balance_ml"] == 160 and d["unknown_ml_packs"] == 1
    assert [p["ml"] for p in d["packs"]] == [80, 80, None]
    assert d["records"][0]["ml_unknown"] == 1


def test_intake_without_pack_mls_is_unknown(client, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    body = _ok(client, nurse_token, pid, kind="intake", count=2)
    assert body["record"]["ml"] is None and body["record"]["ml_unknown"] == 2
    assert body["balance_ml"] == 0 and body["unknown_ml_packs"] == 2


def test_pack_mls_validation(client, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    r = _rec(client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, 80])
    assert r.status_code == 400
    assert r.json()["detail"] == "팩마다 용량 칸 수가 맞지 않습니다"
    for bad in (1001, 0, -5):
        r = _rec(client, nurse_token, pid, kind="intake", count=1, pack_mls=[bad])
        assert r.status_code == 400
        assert r.json()["detail"] == "팩 용량은 1~1000mL 사이여야 합니다"
    assert _rec(client, nurse_token, pid, kind="intake", count=2, pack_mls=[1, 1000]).status_code == 201
    _ok(client, nurse_token, pid, kind="intake", count=1)
    r = _rec(client, nurse_token, pid, kind="use", count=1, pack_mls=[80])
    assert r.status_code == 400
    assert r.json()["detail"] == "입고일 때만 팩 용량을 넣을 수 있습니다"
    r = _rec(client, nurse_token, pid, kind="intake", count=1, pack_ids=[1])
    assert r.status_code == 400
    assert r.json()["detail"] == "사용·폐기일 때만 팩을 고를 수 있습니다"
    # 실패한 요청은 팩을 남기지 않는다
    assert len(_detail(client, nurse_token, pid)["packs"]) == 3


def test_use_takes_oldest_first(client, clock, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    _ok(client, nurse_token, pid, kind="intake", count=1, pack_mls=[80])
    clock.advance(minutes=1)
    _ok(client, nurse_token, pid, kind="intake", count=1, pack_mls=[100])
    body = _ok(client, nurse_token, pid, kind="use", count=1)
    assert body["record"]["ml"] == 80 and body["record"]["ml_unknown"] == 0
    assert body["balance"] == 1 and body["balance_ml"] == 100
    assert [p["ml"] for p in _detail(client, nurse_token, pid)["packs"]] == [100]


def test_use_chosen_packs(client, clock, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    other = _pid(client, admin_token, "P-2")
    _ok(client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, 100, None])
    _ok(client, nurse_token, other, kind="intake", count=1, pack_mls=[50])
    p80, p100, punk = (p["id"] for p in _detail(client, nurse_token, pid)["packs"])
    other_pack = _detail(client, nurse_token, other)["packs"][0]["id"]

    body = _ok(client, nurse_token, pid, kind="use", count=1, pack_ids=[p100])
    assert body["record"]["ml"] == 100
    assert [p["id"] for p in _detail(client, nurse_token, pid)["packs"]] == [p80, punk]

    msg = "고른 팩을 다시 확인해 주세요"
    for ids, count in (
        ([other_pack], 1),  # 다른 아기 팩
        ([p100], 1),  # 이미 나간 팩
        ([p80], 2),  # 수 불일치
        ([p80, p80], 2),  # 같은 id 두 번
    ):
        r = _rec(client, nurse_token, pid, kind="use", count=count, pack_ids=ids)
        assert r.status_code == 409, r.text
        assert r.json()["detail"] == msg
    assert len(_detail(client, nurse_token, pid)["packs"]) == 2

    body = _ok(client, nurse_token, pid, kind="discard", count=1, pack_ids=[punk],
               discard_reason="other")
    assert body["record"]["ml"] is None and body["record"]["ml_unknown"] == 1
    assert [p["id"] for p in _detail(client, nurse_token, pid)["packs"]] == [p80]


def test_cancel_use_returns_packs(client, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    _ok(client, nurse_token, pid, kind="intake", count=2, pack_mls=[80, 100])
    use = _ok(client, nurse_token, pid, kind="use", count=1)["record"]
    assert use["ml"] == 80
    r = client.post(f"/records/{use['id']}/cancel", headers=auth(nurse_token))
    assert r.status_code == 201, r.text
    assert r.json()["record"]["ml"] == 80 and r.json()["record"]["ml_unknown"] == 0
    assert r.json()["balance"] == 2 and r.json()["balance_ml"] == 180
    assert [p["ml"] for p in _detail(client, nurse_token, pid)["packs"]] == [80, 100]


def test_cancel_intake_rules(client, clock, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    a = _ok(client, nurse_token, pid, kind="intake", count=2, pack_mls=[80, 100])["record"]
    clock.advance(minutes=1)
    b = _ok(client, nurse_token, pid, kind="intake", count=2, pack_mls=[70, 70])["record"]
    # 팩이 모두 남은 입고는 취소하면 팩이 사라진다
    r = client.post(f"/records/{b['id']}/cancel", headers=auth(nurse_token))
    assert r.status_code == 201, r.text
    assert r.json()["record"]["ml"] == 140
    assert [p["ml"] for p in _detail(client, nurse_token, pid)["packs"]] == [80, 100]
    # 하나라도 나간 입고는 (잔량이 음수가 되지 않더라도) 취소할 수 없다
    _ok(client, nurse_token, pid, kind="intake", count=2, pack_mls=[60, 60])
    use = _ok(client, nurse_token, pid, kind="use", count=1)["record"]  # a의 80
    r = client.post(f"/records/{a['id']}/cancel", headers=auth(nurse_token))
    assert r.status_code == 409
    assert r.json()["detail"] == "이 입고의 팩이 이미 사용되었습니다. 사용 기록을 먼저 취소해 주세요"
    client.post(f"/records/{use['id']}/cancel", headers=auth(nurse_token))
    assert client.post(f"/records/{a['id']}/cancel", headers=auth(nurse_token)).status_code == 201


def test_balance_equals_pack_count_after_mixed_sequence(client, app, clock, admin_token):
    pid = _pid(client, admin_token)

    def check():
        with app.state.session_factory() as db:
            assert stock.balance(db, pid) == len(packs.in_stock(db, pid))

    def post(path, **body):
        r = client.post(path, json=body or None, headers=auth(admin_token))
        assert r.status_code == 201, r.text
        check()
        clock.advance(minutes=1)
        return r.json()["record"]["id"]

    recs = f"/patients/{pid}/records"
    post(recs, kind="intake", count=3, pack_mls=[80, None, 100])
    u1 = post(recs, kind="use", count=2)
    i2 = post(recs, kind="intake", count=2, pack_mls=[50, 60])
    d1 = post(recs, kind="discard", count=1, discard_reason="contaminated")
    post(f"/records/{u1}/cancel")
    post(f"/records/{i2}/cancel")
    post(recs, kind="use", count=2)
    post(f"/records/{d1}/cancel")
    post(recs, kind="intake", count=1)
    post(recs, kind="use", count=1)


def test_concurrent_use_takes_distinct_packs(client, app, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    _ok(client, nurse_token, pid, kind="intake", count=2, pack_mls=[80, 90])
    with app.state.session_factory() as db:
        nurse_id = db.query(Staff).filter(Staff.username == "nurse1").one().id
    factory = app.state.session_factory
    barrier = threading.Barrier(2)
    errors = []

    def runner():
        session = factory()
        try:
            barrier.wait(timeout=10)
            staff = session.get(Staff, nurse_id)
            stock.add_record(
                session, app.state.clock, patient_id=pid, kind=RecordKind.USE, count=1,
                staff=staff,
            )
            session.commit()
        except Exception as e:  # noqa: BLE001
            session.rollback()
            errors.append(e)
        finally:
            session.close()

    threads = [threading.Thread(target=runner) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    assert errors == []
    with factory() as db:
        out = [p.out_record_id for p in db.query(Pack).filter(Pack.patient_id == pid)]
    assert len(out) == 2 and None not in out and len(set(out)) == 2


def test_record_ml_input_ignored(client, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    body = _ok(client, nurse_token, pid, kind="intake", count=2, ml=999)
    assert body["record"]["ml"] is None
    assert body["record"]["ml_unknown"] == 2


def test_discharge_takes_all_stock_packs(client, app, admin_token, nurse_token):
    pid = _pid(client, admin_token)
    _ok(client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, None, 100])
    r = client.post(
        f"/patients/{pid}/discharge", json={"remaining": "return"}, headers=auth(admin_token)
    )
    assert r.status_code == 200, r.text
    with app.state.session_factory() as db:
        assert packs.in_stock(db, pid) == []
        assert stock.balance(db, pid) == 0
