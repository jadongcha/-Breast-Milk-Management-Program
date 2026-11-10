from datetime import date

import pytest

from milkbank.models import Patient, PatientStatus

from conftest import auth

P = {
    "reg_no": "A123",
    "baby_name": "김아기",
    "bed": "3",
    "guardian_name": "김엄마",
    "guardian_phone": "010-1234-5678",
}


def test_admin_creates_patient(client, admin_token, clock):
    r = client.post("/patients", json=P, headers=auth(admin_token))
    assert r.status_code == 201
    assert r.json()["status"] == "admitted" and r.json()["admitted_on"] == "2026-11-10"


@pytest.mark.parametrize("raw", ["010-1234-5678", "010 1234 5678", "01012345678"])
def test_phone_is_normalized(client, admin_token, raw):
    r = client.post("/patients", json={**P, "guardian_phone": raw}, headers=auth(admin_token))
    assert r.status_code == 201
    assert r.json()["guardian_phone"] == "01012345678"


@pytest.mark.parametrize("raw", ["1234", "02-123-4567", "010-12-34"])
def test_bad_phone_rejected(client, admin_token, raw):
    r = client.post("/patients", json={**P, "guardian_phone": raw}, headers=auth(admin_token))
    assert r.status_code == 400
    assert r.json()["detail"] == "전화번호 형식이 올바르지 않습니다"


def test_reg_no_trimmed_and_unique(client, admin_token):
    r = client.post("/patients", json={**P, "reg_no": " A123 "}, headers=auth(admin_token))
    assert r.json()["reg_no"] == "A123"
    r = client.post("/patients", json=P, headers=auth(admin_token))
    assert r.status_code == 409 and r.json()["detail"] == "이미 등록된 등록번호입니다"


def test_blank_required_field(client, admin_token):
    r = client.post("/patients", json={**P, "baby_name": "  "}, headers=auth(admin_token))
    assert r.status_code == 400 and r.json()["detail"] == "아기 이름을(를) 입력해 주세요"


def test_nurse_cannot_create_or_update(client, admin_token, nurse_token):
    r = client.post("/patients", json=P, headers=auth(nurse_token))
    assert r.status_code == 403 and r.json()["detail"] == "관리자만 사용할 수 있습니다"
    pid = client.post("/patients", json=P, headers=auth(admin_token)).json()["id"]
    r = client.patch(f"/patients/{pid}", json={"bed": "5"}, headers=auth(nurse_token))
    assert r.status_code == 403 and r.json()["detail"] == "관리자만 사용할 수 있습니다"


def test_list_sorted_by_bed_naturally(client, admin_token, nurse_token):
    for i, bed in enumerate(["10", "2", "1"]):
        r = client.post(
            "/patients", json={**P, "reg_no": f"R{i}", "bed": bed}, headers=auth(admin_token)
        )
        assert r.status_code == 201
    r = client.get("/patients", headers=auth(nurse_token))
    assert [p["bed"] for p in r.json()] == ["1", "2", "10"]


def test_search_by_name_bed_regno(client, admin_token, nurse_token):
    client.post("/patients", json=P, headers=auth(admin_token))
    client.post(
        "/patients",
        json={**P, "reg_no": "B999", "baby_name": "박아기", "bed": "7"},
        headers=auth(admin_token),
    )

    def ids(q):
        r = client.get("/patients", params={"q": q}, headers=auth(nurse_token))
        assert r.status_code == 200
        return [p["reg_no"] for p in r.json()]

    assert ids("김아") == ["A123"]
    assert ids("A12") == ["A123"]
    assert ids(" 3 ") == ["A123"]
    assert ids("   ") == ["A123", "B999"]


def test_update_partial(client, admin_token):
    pid = client.post("/patients", json=P, headers=auth(admin_token)).json()["id"]
    r = client.patch(f"/patients/{pid}", json={"bed": "5"}, headers=auth(admin_token))
    assert r.status_code == 200
    body = r.json()
    assert body["bed"] == "5" and body["baby_name"] == "김아기"
    assert body["guardian_phone"] == "01012345678" and body["reg_no"] == "A123"


def test_update_normalizes_and_checks_duplicate(client, admin_token):
    client.post("/patients", json=P, headers=auth(admin_token))
    pid = client.post(
        "/patients", json={**P, "reg_no": "B1"}, headers=auth(admin_token)
    ).json()["id"]
    r = client.patch(f"/patients/{pid}", json={"reg_no": " A123 "}, headers=auth(admin_token))
    assert r.status_code == 409 and r.json()["detail"] == "이미 등록된 등록번호입니다"
    r = client.patch(f"/patients/{pid}", json={"guardian_phone": "010 9999 8888"}, headers=auth(admin_token))
    assert r.json()["guardian_phone"] == "01099998888"
    r = client.patch(f"/patients/{pid}", json={"guardian_name": " "}, headers=auth(admin_token))
    assert r.status_code == 400 and r.json()["detail"] == "보호자 이름을(를) 입력해 주세요"
    # 같은 환자 자신의 등록번호를 그대로 보내도 충돌이 아니다
    r = client.patch(f"/patients/{pid}", json={"reg_no": "B1"}, headers=auth(admin_token))
    assert r.status_code == 200


def test_update_ignores_status(client, admin_token):
    pid = client.post("/patients", json=P, headers=auth(admin_token)).json()["id"]
    r = client.patch(
        f"/patients/{pid}", json={"status": "discharged", "discharged_on": "2026-11-10"},
        headers=auth(admin_token),
    )
    assert r.status_code == 200 and r.json()["status"] == "admitted"
    assert r.json()["discharged_on"] is None


def test_threshold_range(client, admin_token):
    r = client.post("/patients", json={**P, "days_threshold": 31}, headers=auth(admin_token))
    assert r.status_code == 422
    r = client.post("/patients", json={**P, "packs_threshold": 0}, headers=auth(admin_token))
    assert r.status_code == 422


def test_get_patient_and_unknown(client, admin_token, nurse_token):
    pid = client.post("/patients", json=P, headers=auth(admin_token)).json()["id"]
    assert client.get(f"/patients/{pid}", headers=auth(nurse_token)).json()["reg_no"] == "A123"
    r = client.get("/patients/9999", headers=auth(nurse_token))
    assert r.status_code == 404 and r.json()["detail"] == "환자를 찾을 수 없습니다"


def test_discharged_hidden_by_default(client, app, admin_token, nurse_token):
    client.post("/patients", json=P, headers=auth(admin_token))
    with app.state.session_factory() as db:
        db.add(
            Patient(
                reg_no="D1", baby_name="퇴원", bed="1", guardian_name="g",
                guardian_phone="01011112222", status=PatientStatus.DISCHARGED,
                admitted_on=date(2026, 11, 1), discharged_on=date(2026, 11, 9),
                created_at=app.state.clock.now(),
            )
        )
        db.commit()
    r = client.get("/patients", headers=auth(nurse_token))
    assert [p["reg_no"] for p in r.json()] == ["A123"]
    r = client.get("/patients", params={"include_discharged": "true"}, headers=auth(nurse_token))
    assert [p["reg_no"] for p in r.json()] == ["D1", "A123"]
