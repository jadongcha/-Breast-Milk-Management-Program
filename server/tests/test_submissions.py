import threading

from conftest import _gauth, _login, _patient, auth, make_staff
from sqlalchemy import select

from milkbank import submissions
from milkbank.errors import Conflict
from milkbank.models import (
    Patient,
    PatientStatus,
    RecordKind,
    Role,
    Staff,
    StockRecord,
    Submission,
    SubmissionStatus,
)


def _gtoken(client, st, baby="DEMO-01"):
    return _login(client, st, baby=baby).json()["token"]


def _submit(client, st, token, patient_id, count):
    return client.post(
        "/guardian/submissions",
        json={"patient_id": patient_id, "count": count},
        headers=_gauth(st, token),
    )


def _pending(client, token):
    r = client.get("/submissions?status=pending", headers=auth(token))
    assert r.status_code == 200, r.text
    return r.json()


def _discharge(app, patient_id):
    with app.state.session_factory() as db:
        db.get(Patient, patient_id).status = PatientStatus.DISCHARGED
        db.commit()


def test_submit_then_pending_list(client, nurse_token, world):
    st = world["a"]
    r = _submit(client, st, _gtoken(client, st), world["p1"], 3)
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["balance"] == 3 and body["pending_count"] == 3
    assert body["submission"]["count"] == 3 and body["submission"]["id"]
    rows = _pending(client, nurse_token)
    assert len(rows) == 1
    row = rows[0]
    assert row["station_name"] == "제출처 A" and row["discharged"] is False
    assert row["patient_id"] == world["p1"] and row["baby_id"] == "DEMO-01"
    assert row["balance"] == 3 and row["count"] == 3 and row["id"] == body["submission"]["id"]


def test_submit_rules(client, app, admin_token, world):
    st = world["a"]
    token = _gtoken(client, st)
    for n in (0, 11):
        r = _submit(client, st, token, world["p1"], n)
        assert r.status_code == 400 and r.json()["detail"] == "팩 수는 1~10 사이여야 합니다"
    other = _patient(client, admin_token, "OTHER-01", phone="010-9999-0000")
    r = _submit(client, st, token, other, 1)
    assert r.status_code == 403 and r.json()["detail"] == "본인 아기만 제출할 수 있습니다"
    r = _submit(client, st, token, 99999, 1)
    assert r.status_code == 403
    _discharge(app, world["p2"])
    r = _submit(client, st, token, world["p2"], 1)
    assert r.status_code == 409 and r.json()["detail"] == "퇴원한 아기입니다"


def test_pending_limit_five(client, world):
    st = world["a"]
    token = _gtoken(client, st)
    for _ in range(5):
        assert _submit(client, st, token, world["p1"], 1).status_code == 201
    r = _submit(client, st, token, world["p1"], 1)
    assert r.status_code == 409
    assert r.json()["detail"] == "확인을 기다리는 제출이 많습니다. 간호사에게 말씀해 주세요"


def test_confirm_with_corrected_count(client, nurse_token, world):
    st = world["a"]
    gt = _gtoken(client, st)
    sid = _submit(client, st, gt, world["p1"], 3).json()["submission"]["id"]
    r = client.post(f"/submissions/{sid}/confirm", json={"count": 2}, headers=auth(nurse_token))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["balance"] == 5
    assert body["record"]["kind"] == "in" and body["record"]["count"] == 2
    assert body["record"]["memo"] == "제출처 A 제출분(제출 3팩)"
    me = client.get("/auth/me", headers=auth(nurse_token)).json()
    assert body["record"]["staff_id"] == me["id"]
    sub = body["submission"]
    assert sub["status"] == "confirmed" and sub["confirmed_count"] == 2
    assert sub["handled_by"] == me["id"] and sub["handled_at"]
    assert _pending(client, nurse_token) == []
    babies = client.get("/guardian/babies", headers=_gauth(st, gt)).json()
    assert babies[0]["pending_count"] == 0 and babies[0]["balance"] == 5


def test_confirm_count_range(client, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    for n in (0, 101):
        r = client.post(
            f"/submissions/{sid}/confirm", json={"count": n}, headers=auth(nurse_token)
        )
        assert r.status_code == 400 and r.json()["detail"] == "팩 수는 1~100 사이여야 합니다"
    assert len(_pending(client, nurse_token)) == 1


def test_confirm_twice_conflict(client, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    url = f"/submissions/{sid}/confirm"
    assert client.post(url, json={"count": 3}, headers=auth(nurse_token)).status_code == 200
    r = client.post(url, json={"count": 3}, headers=auth(nurse_token))
    assert r.status_code == 409 and r.json()["detail"] == "이미 처리된 제출입니다"
    r = client.post(
        f"/submissions/{sid}/reject", json={"reason": "not_received"},
        headers=auth(nurse_token),
    )
    assert r.status_code == 409 and r.json()["detail"] == "이미 처리된 제출입니다"


def test_unknown_submission_404(client, nurse_token):
    for path, body in (("confirm", {"count": 1}), ("reject", {"reason": "not_received"})):
        r = client.post(f"/submissions/999/{path}", json=body, headers=auth(nurse_token))
        assert r.status_code == 404 and r.json()["detail"] == "제출을 찾을 수 없습니다"


def test_concurrent_confirm_only_one_wins(client, app, clock, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    make_staff(client, app, Role.NURSE, "n-conc-1")
    with app.state.session_factory() as db:
        # 직원은 미리 읽어 둔다. 그래야 각 스레드의 첫 쿼리가 제출 조회(쓰기 잠금)가 된다.
        staff = db.scalars(select(Staff).where(Staff.username == "n-conc-1")).one()
    barrier = threading.Barrier(2, timeout=10)
    results = []

    def worker():
        db = app.state.session_factory()
        try:
            barrier.wait()
            try:
                submissions.confirm(db, clock, submission_id=sid, count=3, staff=staff)
                db.commit()
                results.append("ok")
            except Conflict:
                db.rollback()
                results.append("conflict")
        except BaseException as e:  # noqa: BLE001
            db.rollback()
            results.append(repr(e))
        finally:
            db.close()

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    assert not any(t.is_alive() for t in threads)
    assert sorted(results) == ["conflict", "ok"], results
    with app.state.session_factory() as db:
        intakes = db.scalars(
            select(StockRecord).where(
                StockRecord.patient_id == world["p1"], StockRecord.memo.is_not(None)
            )
        ).all()
        assert len(intakes) == 1 and intakes[0].kind == RecordKind.INTAKE


def test_reject_reasons(client, nurse_token, world):
    st = world["a"]
    gt = _gtoken(client, st)
    ids = [_submit(client, st, gt, world["p1"], 2).json()["submission"]["id"] for _ in range(2)]
    h = auth(nurse_token)
    r = client.post(f"/submissions/{ids[0]}/reject", json={"reason": "not_received"}, headers=h)
    assert r.status_code == 200, r.text
    assert r.json()["submission"]["status"] == "rejected"
    assert r.json()["submission"]["reject_reason"] == "not_received"
    assert client.get("/guardian/babies", headers=_gauth(st, gt)).json()[0]["balance"] == 3
    for body in ({"reason": "other"}, {"reason": "other", "memo": "   "}):
        r = client.post(f"/submissions/{ids[1]}/reject", json=body, headers=h)
        assert r.status_code == 400 and r.json()["detail"] == "기타 사유를 입력해 주세요"
    r = client.post(
        f"/submissions/{ids[1]}/reject", json={"reason": "other", "memo": "가" * 101}, headers=h
    )
    assert r.status_code == 400 and r.json()["detail"] == "메모는 100자 이하로 입력해 주세요"
    r = client.post(
        f"/submissions/{ids[1]}/reject", json={"reason": "other", "memo": "  포장 훼손  "},
        headers=h,
    )
    assert r.status_code == 200
    assert r.json()["submission"]["memo"] == "포장 훼손"
    assert r.json()["submission"]["handled_by"]


def test_discharged_after_submit(client, app, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    _discharge(app, world["p1"])
    rows = _pending(client, nurse_token)
    assert len(rows) == 1 and rows[0]["discharged"] is True
    r = client.post(f"/submissions/{sid}/confirm", json={"count": 3}, headers=auth(nurse_token))
    assert r.status_code == 409
    assert r.json()["detail"] == "퇴원한 아기입니다. 반려만 할 수 있습니다"
    r = client.post(
        f"/submissions/{sid}/reject", json={"reason": "not_received"},
        headers=auth(nurse_token),
    )
    assert r.status_code == 200


def test_pending_list_status_filter_and_order(client, nurse_token, world):
    st = world["a"]
    gt = _gtoken(client, st)
    a = _submit(client, st, gt, world["p1"], 1).json()["submission"]["id"]
    b = _submit(client, st, gt, world["p1"], 2).json()["submission"]["id"]
    assert [r["id"] for r in _pending(client, nurse_token)] == [a, b]
    r = client.get("/submissions?status=confirmed", headers=auth(nurse_token))
    assert r.status_code == 422


def test_guardian_cannot_call_staff_submission_api(client, world):
    st = world["a"]
    gt = _gtoken(client, st)
    assert client.get("/submissions", headers=_gauth(st, gt)).status_code == 401
    assert client.get("/submissions", headers=auth(gt)).status_code == 401


def test_staff_token_cannot_submit(client, nurse_token, world):
    r = client.post(
        "/guardian/submissions", json={"patient_id": world["p1"], "count": 1},
        headers=_gauth(world["a"], nurse_token),
    )
    assert r.status_code == 401


def test_cancel_confirmed_intake_keeps_submission_confirmed(client, app, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 2).json()["submission"]["id"]
    r = client.post(f"/submissions/{sid}/confirm", json={"count": 2}, headers=auth(nurse_token))
    rid = r.json()["record"]["id"]
    assert r.json()["balance"] == 5
    r = client.post(f"/records/{rid}/cancel", headers=auth(nurse_token))
    assert r.status_code == 201 and r.json()["balance"] == 3
    with app.state.session_factory() as db:
        assert db.get(Submission, sid).status == SubmissionStatus.CONFIRMED


def test_pending_limit_is_per_baby(client, world):
    st = world["a"]
    token = _gtoken(client, st)
    for _ in range(5):
        assert _submit(client, st, token, world["p1"], 1).status_code == 201
    assert _submit(client, st, token, world["p2"], 1).status_code == 201


def test_confirm_links_intake_record(client, app, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    r = client.post(f"/submissions/{sid}/confirm", json={"count": 3}, headers=auth(nurse_token))
    assert r.status_code == 200, r.text
    rec_id = r.json()["record"]["id"]
    with app.state.session_factory() as db:
        sub = db.get(Submission, sid)
        assert sub.intake_record_id == rec_id
        assert db.get(StockRecord, rec_id).kind == RecordKind.INTAKE


def test_confirm_ml_range(client, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    for ml in (0, 20001):
        r = client.post(
            f"/submissions/{sid}/confirm", json={"count": 3, "ml": ml}, headers=auth(nurse_token)
        )
        assert r.status_code == 400 and r.json()["detail"] == "용량은 1~20000mL 사이여야 합니다"
    assert len(_pending(client, nurse_token)) == 1


def test_reject_erased_patient_stores_no_memo(client, app, admin_token, nurse_token, world):
    st = world["a"]
    sid = _submit(client, st, _gtoken(client, st), world["p1"], 3).json()["submission"]["id"]
    assert client.post(
        f"/patients/{world['p1']}/discharge", json={"remaining": "return"}, headers=auth(admin_token)
    ).status_code == 200
    assert client.post(f"/patients/{world['p1']}/erase", headers=auth(admin_token)).status_code == 200
    r = client.post(
        f"/submissions/{sid}/reject", json={"reason": "other", "memo": "개인정보 메모"},
        headers=auth(nurse_token),
    )
    assert r.status_code == 200, r.text
    assert r.json()["submission"]["memo"] is None
    with app.state.session_factory() as db:
        assert db.get(Submission, sid).memo is None
