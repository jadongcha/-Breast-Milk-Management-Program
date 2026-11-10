import threading

import pytest

from milkbank import staff as staff_service
from milkbank.db import make_session_factory
from milkbank.errors import BadRequest, Conflict, Forbidden, NotFound
from milkbank.models import DiscardReason, PatientStatus, RecordKind, Role, Staff
from milkbank.patients import create_patient
from milkbank.schemas import PatientCreate
from milkbank.stock import add_record, balance, cancel_record, recent_records

INTAKE, USE, DISCARD, RETURN = (
    RecordKind.INTAKE,
    RecordKind.USE,
    RecordKind.DISCARD,
    RecordKind.RETURN,
)
WINDOW = 10


@pytest.fixture
def admin(db, clock):
    s, _ = staff_service.create_staff(
        db, clock, username="admin", name="관리자", role=Role.ADMIN
    )
    return s


@pytest.fixture
def nurse(db, clock):
    s, _ = staff_service.create_staff(
        db, clock, username="nurse", name="간호사", role=Role.NURSE
    )
    return s


@pytest.fixture
def nurse2(db, clock):
    s, _ = staff_service.create_staff(
        db, clock, username="nurse2", name="간호사2", role=Role.NURSE
    )
    return s


@pytest.fixture
def p(db, clock, admin):
    pt = create_patient(
        db,
        clock,
        PatientCreate(
            reg_no="A1",
            baby_name="아기",
            bed="1",
            guardian_name="보호자",
            guardian_phone="010-1234-5678",
        ),
    )
    db.commit()
    return pt


@pytest.fixture
def add(db, clock, nurse, p):
    def _add(kind, count, *, reason=None, memo=None, staff=None):
        return add_record(
            db,
            clock,
            patient_id=p.id,
            kind=kind,
            count=count,
            staff=staff or nurse,
            discard_reason=reason,
            memo=memo,
        )

    return _add


@pytest.fixture
def cancel(db, clock):
    def _cancel(record_id, staff):
        return cancel_record(
            db,
            clock,
            record_id=record_id,
            staff=staff,
            cancel_window_minutes=WINDOW,
        )

    return _cancel


def test_balance_math(db, p, add):
    add(INTAKE, 5)
    add(USE, 1)
    add(DISCARD, 1, reason=DiscardReason.THAWED_LEFTOVER)
    assert balance(db, p.id) == 3


def test_return_reduces_balance(db, p, add):
    add(INTAKE, 5)
    add(RETURN, 2)
    assert balance(db, p.id) == 3


def test_use_more_than_balance(add):
    add(INTAKE, 2)
    with pytest.raises(Conflict, match=r"^재고가 부족합니다 \(현재 2팩\)$"):
        add(USE, 3)


def test_use_on_zero_stock(add):
    with pytest.raises(Conflict, match=r"^재고가 부족합니다 \(현재 0팩\)$"):
        add(USE, 1)


@pytest.mark.parametrize("n", [0, 101])
def test_count_bounds(add, n):
    with pytest.raises(BadRequest, match="^팩 수는 1~100 사이여야 합니다$"):
        add(INTAKE, n)


def test_discard_needs_reason(add):
    add(INTAKE, 2)
    with pytest.raises(BadRequest, match="^폐기 사유를 선택해 주세요$"):
        add(DISCARD, 1)


def test_reason_only_for_discard(add):
    with pytest.raises(BadRequest, match="^폐기일 때만 사유를 넣을 수 있습니다$"):
        add(INTAKE, 1, reason=DiscardReason.OTHER)


def test_cancel_kind_rejected(add):
    with pytest.raises(BadRequest, match="^잘못된 기록 종류입니다$"):
        add(RecordKind.CANCEL, 1)


def test_patient_not_found(db, clock, nurse):
    with pytest.raises(NotFound, match="^환자를 찾을 수 없습니다$"):
        add_record(db, clock, patient_id=999, kind=INTAKE, count=1, staff=nurse)


def test_memo_trimmed(add):
    assert add(INTAKE, 1, memo="  hello  ").memo == "hello"
    assert add(INTAKE, 1, memo="   ").memo is None


def test_discharged_patient_blocked(db, p, add, cancel, nurse):
    r = add(INTAKE, 2)
    p.status = PatientStatus.DISCHARGED
    db.flush()
    with pytest.raises(Conflict, match="^퇴원한 환자입니다$"):
        add(INTAKE, 1)
    with pytest.raises(Conflict, match="^퇴원한 환자입니다$"):
        cancel(r.id, nurse)


def test_cancel_restores_balance(db, p, add, cancel, nurse):
    add(INTAKE, 5)
    u = add(USE, 2)
    c = cancel(u.id, nurse)
    assert balance(db, p.id) == 5
    assert c.kind == RecordKind.CANCEL
    assert c.count == 2
    assert c.target_id == u.id
    assert c.staff_id == nurse.id


def test_cancel_missing(cancel, nurse):
    with pytest.raises(NotFound, match="^기록을 찾을 수 없습니다$"):
        cancel(999, nurse)


def test_cancel_twice(add, cancel, nurse):
    add(INTAKE, 5)
    u = add(USE, 2)
    cancel(u.id, nurse)
    with pytest.raises(Conflict, match="^이미 취소된 기록입니다$"):
        cancel(u.id, nurse)


def test_cancel_a_cancel(add, cancel, nurse, admin):
    add(INTAKE, 5)
    c = cancel(add(USE, 2).id, nurse)
    with pytest.raises(Conflict, match="^취소 기록은 취소할 수 없습니다$"):
        cancel(c.id, admin)


def test_nurse_cannot_cancel_others(add, cancel, nurse2):
    r = add(INTAKE, 3)
    with pytest.raises(Forbidden, match="^본인이 입력한 기록만 취소할 수 있습니다$"):
        cancel(r.id, nurse2)


def test_admin_can_cancel_others(db, p, add, cancel, admin):
    r = add(INTAKE, 3)
    cancel(r.id, admin)
    assert balance(db, p.id) == 0


def test_nurse_cancel_window(clock, add, cancel, nurse, admin):
    r1 = add(INTAKE, 3)
    clock.advance(minutes=10)
    cancel(r1.id, nurse)  # 정각 허용
    r2 = add(INTAKE, 3)
    clock.advance(minutes=10, seconds=1)
    with pytest.raises(
        Forbidden, match="^입력 후 10분이 지나 관리자만 취소할 수 있습니다$"
    ):
        cancel(r2.id, nurse)
    cancel(r2.id, admin)  # 관리자는 언제든


def test_cancel_intake_would_go_negative(add, cancel, nurse):
    i = add(INTAKE, 3)
    add(USE, 2)
    with pytest.raises(Conflict, match="^취소하면 재고가 음수가 됩니다$"):
        cancel(i.id, nurse)


def test_recent_records_marks_canceled(db, p, clock, add, cancel, nurse):
    add(INTAKE, 5)
    clock.advance(minutes=1)
    u = add(USE, 2)
    clock.advance(minutes=1)
    cancel(u.id, nurse)
    views = recent_records(db, p.id)
    assert [v.kind for v in views] == [
        RecordKind.CANCEL,
        RecordKind.USE,
        RecordKind.INTAKE,
    ]
    assert views[0].canceled is False
    assert views[0].target_id == u.id
    assert views[1].canceled is True
    assert views[2].canceled is False
    assert all(v.staff_name == "간호사" for v in views)


def test_recent_records_limit(db, p, add):
    for _ in range(3):
        add(INTAKE, 1)
    assert len(recent_records(db, p.id, limit=2)) == 2


def _run_concurrently(engine, work):
    """work(session)을 두 스레드에서 동시에 실행한다. 결과: None(성공) 또는 예외."""
    factory = make_session_factory(engine)
    barrier = threading.Barrier(2)
    results = [None, None]

    def runner(i):
        session = factory()
        try:
            barrier.wait(timeout=10)
            work(session)
            session.commit()
        except Exception as e:  # noqa: BLE001
            session.rollback()
            results[i] = e
        finally:
            session.close()

    threads = [threading.Thread(target=runner, args=(i,)) for i in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    return results


def _fresh_balance(engine, patient_id):
    with make_session_factory(engine)() as s:
        return balance(s, patient_id)


def test_concurrent_use_only_one_succeeds(engine, db, clock, nurse, p, add):
    add(INTAKE, 1)
    nurse_id = nurse.id
    db.commit()
    db.close()

    def work(session):
        staff = session.get(Staff, nurse_id)
        add_record(session, clock, patient_id=p.id, kind=USE, count=1, staff=staff)

    results = _run_concurrently(engine, work)
    errors = [r for r in results if r is not None]
    assert len(errors) == 1
    assert isinstance(errors[0], Conflict)
    assert errors[0].message == "재고가 부족합니다 (현재 0팩)"
    assert _fresh_balance(engine, p.id) == 0


def test_concurrent_cancel_only_one_succeeds(engine, db, clock, nurse, p, add):
    add(INTAKE, 5)
    u = add(USE, 2)
    nurse_id, uid = nurse.id, u.id
    db.commit()
    db.close()

    def work(session):
        staff = session.get(Staff, nurse_id)
        cancel_record(
            session,
            clock,
            record_id=uid,
            staff=staff,
            cancel_window_minutes=WINDOW,
        )

    results = _run_concurrently(engine, work)
    errors = [r for r in results if r is not None]
    assert len(errors) == 1
    assert isinstance(errors[0], Conflict)
    assert errors[0].message == "이미 취소된 기록입니다"
    assert _fresh_balance(engine, p.id) == 5
