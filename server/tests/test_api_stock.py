from conftest import auth

P = {
    "reg_no": "A123",
    "baby_name": "김아기",
    "bed": "3",
    "guardian_name": "김엄마",
    "guardian_phone": "010-1234-5678",
}


def create_patient(client, admin_token) -> int:
    r = client.post("/patients", json=P, headers=auth(admin_token))
    assert r.status_code == 201
    assert r.json()["balance"] == 0
    return r.json()["id"]


def rec(client, token, pid, kind, count, **extra):
    return client.post(
        f"/patients/{pid}/records",
        json={"kind": kind, "count": count, **extra},
        headers=auth(token),
    )


def test_intake_use_flow(client, admin_token, nurse_token):
    pid = create_patient(client, admin_token)
    r = rec(client, nurse_token, pid, "intake", 4)
    assert r.status_code == 201
    assert r.json()["balance"] == 4 and r.json()["record"]["kind"] == "in"
    assert r.json()["record"]["staff_name"] == "nurse1"
    r = rec(client, nurse_token, pid, "use", 1)
    assert r.json()["balance"] == 3


def test_list_and_detail_show_balance(client, admin_token, nurse_token):
    pid = create_patient(client, admin_token)
    rec(client, nurse_token, pid, "intake", 4)
    rec(client, nurse_token, pid, "use", 1)
    lst = client.get("/patients", headers=auth(nurse_token)).json()
    assert lst[0]["balance"] == 3
    d = client.get(f"/patients/{pid}", headers=auth(nurse_token)).json()
    assert d["balance"] == 3
    assert [x["kind"] for x in d["records"]] == ["use", "in"]
    assert d["records"][0]["staff_name"] == "nurse1"


def test_cancel_via_api(client, admin_token, nurse_token):
    pid = create_patient(client, admin_token)
    rec(client, nurse_token, pid, "intake", 4)
    use_id = rec(client, nurse_token, pid, "use", 1).json()["record"]["id"]
    r = client.post(f"/records/{use_id}/cancel", headers=auth(nurse_token))
    assert r.status_code == 201
    assert r.json()["balance"] == 4 and r.json()["record"]["kind"] == "cancel"
    d = client.get(f"/patients/{pid}", headers=auth(nurse_token)).json()
    orig = next(x for x in d["records"] if x["id"] == use_id)
    assert orig["canceled"] is True


def test_error_messages_pass_through(client, admin_token, nurse_token):
    pid = create_patient(client, admin_token)
    r = rec(client, nurse_token, pid, "use", 1)
    assert r.status_code == 409
    assert r.json() == {"detail": "재고가 부족합니다 (현재 0팩)"}


def test_return_kind_not_allowed_via_api(client, admin_token, nurse_token):
    pid = create_patient(client, admin_token)
    assert rec(client, nurse_token, pid, "return", 1).status_code == 422


def test_unauthenticated_record(client):
    r = client.post("/patients/1/records", json={"kind": "intake", "count": 1})
    assert r.status_code == 401
    assert client.post("/records/1/cancel").status_code == 401
