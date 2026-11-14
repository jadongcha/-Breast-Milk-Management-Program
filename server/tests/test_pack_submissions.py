from conftest import _gauth, _login, auth

SIZE_MSG = "팩마다 용량 칸 수가 맞지 않습니다"
RANGE_MSG = "팩 용량은 1~1000mL 사이여야 합니다"


def _gtoken(client, st):
    return _login(client, st).json()["token"]


def _submit(client, st, token, pid, count=3, **extra):
    return client.post(
        "/guardian/submissions",
        json={"patient_id": pid, "count": count, **extra},
        headers=_gauth(st, token),
    )


def _pending(client, token):
    return client.get("/submissions?status=pending", headers=auth(token)).json()


def _confirm(client, token, sid, body):
    return client.post(f"/submissions/{sid}/confirm", json=body, headers=auth(token))


def _packs(client, token, pid):
    d = client.get(f"/patients/{pid}", headers=auth(token)).json()
    return [p["ml"] for p in d["packs"]]


def test_submit_pack_mls(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    r = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 100])
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["submission"]["ml"] == 260
    assert body["balance_ml"] == 0 and body["unknown_ml_packs"] == 0
    rows = _pending(client, nurse_token)
    assert rows[0]["pack_mls"] == [80, 80, 100] and rows[0]["ml"] == 260
    babies = client.get("/guardian/babies", headers=_gauth(st, token)).json()
    assert "balance_ml" in babies[0] and "unknown_ml_packs" in babies[0]
    # 안 넣으면 모두 모름
    r = _submit(client, st, token, world["p2"], 2)
    assert r.json()["submission"]["ml"] is None
    assert _pending(client, nurse_token)[1]["pack_mls"] == [None, None]


def test_submit_pack_mls_validation(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    r = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80])
    assert r.status_code == 400 and r.json()["detail"] == SIZE_MSG
    r = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 1001])
    assert r.status_code == 400 and r.json()["detail"] == RANGE_MSG
    assert _pending(client, nurse_token) == []


def test_confirm_pack_mls_default_and_explicit(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    a = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 100]).json()["submission"]["id"]
    b = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 100]).json()["submission"]["id"]
    r = _confirm(client, nurse_token, a, {"count": 3})
    assert r.status_code == 200, r.text
    assert r.json()["record"]["ml"] == 260
    assert r.json()["submission"]["confirmed_ml"] == 260
    assert _packs(client, nurse_token, world["p2"]) == [80, 80, 100]
    r = _confirm(client, nurse_token, b, {"count": 3, "pack_mls": [90, None, 100]})
    assert r.status_code == 200, r.text
    assert r.json()["record"]["ml"] == 190 and r.json()["record"]["ml_unknown"] == 1
    assert r.json()["submission"]["confirmed_ml"] == 190
    assert _packs(client, nurse_token, world["p2"]) == [80, 80, 100, 90, None, 100]


def test_confirm_pack_mls_validation(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    sid = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 100]).json()["submission"]["id"]
    r = _confirm(client, nurse_token, sid, {"count": 3, "pack_mls": [1, 2]})
    assert r.status_code == 400 and r.json()["detail"] == SIZE_MSG
    r = _confirm(client, nurse_token, sid, {"count": 3, "pack_mls": [1, 2, 0]})
    assert r.status_code == 400 and r.json()["detail"] == RANGE_MSG
    assert len(_pending(client, nurse_token)) == 1


def test_confirm_resizes_guardian_pack_mls(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    a = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 100]).json()["submission"]["id"]
    b = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, 100]).json()["submission"]["id"]
    assert _confirm(client, nurse_token, a, {"count": 2}).status_code == 200
    assert _packs(client, nurse_token, world["p2"]) == [80, 80]
    assert _confirm(client, nurse_token, b, {"count": 4}).status_code == 200
    assert _packs(client, nurse_token, world["p2"]) == [80, 80, 80, 80, 100, None]


def test_confirm_result_balance_ml(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    sid = _submit(client, st, token, world["p2"], 3, pack_mls=[80, 80, None]).json()["submission"]["id"]
    r = _confirm(client, nurse_token, sid, {"count": 3})
    body = r.json()
    assert body["balance"] == 3 and body["balance_ml"] == 160 and body["unknown_ml_packs"] == 1


def test_old_ml_input_is_ignored(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    r = _submit(client, st, token, world["p2"], 2, ml=240)
    assert r.status_code == 201 and r.json()["submission"]["ml"] is None
    sid = r.json()["submission"]["id"]
    r = _confirm(client, nurse_token, sid, {"count": 2, "ml": 99999})
    assert r.status_code == 200 and r.json()["record"]["ml"] is None


def test_submit_and_confirm_pack_mls_strict(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    for bad in (["80", 80, 80], [True, 80, 80], [90.0, 80, 80]):
        assert _submit(client, st, token, world["p2"], 3, pack_mls=bad).status_code == 422, bad
    assert _pending(client, nurse_token) == []
    assert _submit(client, st, token, world["p2"], 3).status_code == 201
    sid = _pending(client, nurse_token)[0]["id"]
    for bad in (["80", 80, 80], [True, 80, 80], [90.0, 80, 80]):
        r = _confirm(client, nurse_token, sid, {"count": 3, "pack_mls": bad})
        assert r.status_code == 422, bad
    assert _packs(client, nurse_token, world["p2"]) == []
