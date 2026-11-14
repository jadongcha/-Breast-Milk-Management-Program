from conftest import _gauth, _login, auth

MSG = "팩 용량은 1~1000mL 사이여야 합니다"


def _record(client, token, pid, **body):
    return client.post(f"/patients/{pid}/records", json=body, headers=auth(token))


def _submit(client, st, token, pid, **extra):
    return client.post(
        "/guardian/submissions",
        json={"patient_id": pid, "count": 3, **extra},
        headers=_gauth(st, token),
    )


def _gtoken(client, st):
    return _login(client, st).json()["token"]


def test_record_with_ml(client, nurse_token, world):
    pid = world["p1"]
    r = _record(client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, 80, 80])
    assert r.status_code == 201, r.text
    assert r.json()["record"]["ml"] == 240
    detail = client.get(f"/patients/{pid}", headers=auth(nurse_token)).json()
    assert detail["records"][0]["ml"] == 240
    r = _record(client, nurse_token, pid, kind="intake", count=1)
    assert r.status_code == 201 and r.json()["record"]["ml"] is None


def test_record_ml_range(client, nurse_token, world):
    pid = world["p1"]
    for bad in (0, 1001):
        r = _record(client, nurse_token, pid, kind="intake", count=1, pack_mls=[bad])
        assert r.status_code == 400 and r.json()["detail"] == "팩 용량은 1~1000mL 사이여야 합니다"
    assert _record(client, nurse_token, pid, kind="intake", count=1, pack_mls=[1000]).status_code == 201


def test_cancel_copies_ml(client, nurse_token, world):
    pid = world["p1"]
    rec = _record(
        client, nurse_token, pid, kind="intake", count=3, pack_mls=[80, 80, 80]
    ).json()["record"]
    r = client.post(f"/records/{rec['id']}/cancel", headers=auth(nurse_token))
    assert r.status_code == 201, r.text
    assert r.json()["record"]["ml"] == 240


def test_guardian_submit_ml(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    r = _submit(client, st, token, world["p1"], pack_mls=[80, 80, 80])
    assert r.status_code == 201, r.text
    assert r.json()["submission"]["ml"] == 240
    rows = client.get("/submissions?status=pending", headers=auth(nurse_token)).json()
    assert rows[0]["ml"] == 240
    r = _submit(client, st, token, world["p1"], pack_mls=[0, 80, 80])
    assert r.status_code == 400 and r.json()["detail"] == MSG
    r = _submit(client, st, token, world["p1"])
    assert r.json()["submission"]["ml"] is None


def test_confirm_ml_default_and_clear(client, nurse_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    ids = [
        _submit(client, st, token, world["p1"], pack_mls=[80, 80, 80]).json()["submission"]["id"]
        for _ in range(3)
    ]

    def confirm(i, body):
        r = client.post(f"/submissions/{ids[i]}/confirm", json=body, headers=auth(nurse_token))
        assert r.status_code == 200, r.text
        return r.json()

    a = confirm(0, {"count": 3})
    assert a["submission"]["confirmed_ml"] == 240 and a["record"]["ml"] == 240
    b = confirm(1, {"count": 3, "pack_mls": [70, 70, 60]})
    assert b["submission"]["confirmed_ml"] == 200 and b["record"]["ml"] == 200
    c = confirm(2, {"count": 3, "pack_mls": None})
    assert c["submission"]["confirmed_ml"] is None and c["record"]["ml"] is None
    assert c["submission"]["ml"] == 240
