"""데모 DB와 계정 파일을 만든다.

사용법: python scripts/make_demo_db.py <DB 경로> <계정 파일 경로> [--today YYYY-MM-DD]

앱의 서비스 함수(staff, patients, stock, submissions)로 지난 14일치 기록을 넣으므로
잔량·팩·통계가 실제 사용 경로와 똑같이 만들어진다. 비밀번호는 만들 때마다 무작위다.
"""
import argparse
import random
import sys
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from pathlib import Path

from sqlalchemy.orm import Session

from milkbank import patients, stock, submissions
from milkbank.cli import _utf8_streams
from milkbank import staff as staff_service
from milkbank.clock import KST, FakeClock
from milkbank.config import Config
from milkbank.db import make_engine
from milkbank.migrate import migrate
from milkbank.models import DiscardReason, GuardianSession, RecordKind, Role
from milkbank.schemas import PatientCreate
from milkbank.security import generate_temp_password, hash_password, new_token

DAYS = 14
# 하루 안의 순서: (칸 이름, 시각). 오늘은 마지막 칸(오후)을 건너뛴다.
SLOTS = [("in", time(8, 0)), ("use", time(8, 30)), ("discard", time(9, 0)), ("use2", time(13, 0))]
ML_CHOICES = [60, 80, 100, 120]


@dataclass
class Plan:
    """아기 한 명의 지난 14일. 키는 '며칠 전'(14=14일 전, 0=오늘)."""

    intake: dict[int, int]
    use_from: int  # 이 날부터 use_until 날까지 매일 use_per_day팩 사용
    use_per_day: int
    use_until: int = 0
    unknown_ratio: float = 0.0
    discard: dict[int, tuple[int, DiscardReason]] = field(default_factory=dict)


PLANS = [
    Plan({14: 12, 7: 12}, use_from=13, use_per_day=1),  # 01 넉넉
    Plan({13: 10, 6: 8}, use_from=12, use_per_day=1, unknown_ratio=0.25),  # 02 넉넉
    Plan({12: 12}, use_from=11, use_per_day=1),  # 03 부족(얼마 안 남음)
    Plan({10: 6}, use_from=9, use_per_day=1, use_until=4),  # 04 0팩
    Plan({2: 3}, use_from=1, use_per_day=1),  # 05 새 아기, 부족
    Plan({5: 6}, use_from=4, use_per_day=1, unknown_ratio=0.8),  # 06 용량 모르는 팩이 많음
    Plan({1: 2}, use_from=0, use_per_day=2),  # 07 0팩
    Plan(
        {14: 8, 4: 8},
        use_from=13,
        use_per_day=1,
        discard={6: (1, DiscardReason.THAWED_LEFTOVER), 2: (1, DiscardReason.CONTAMINATED)},
    ),  # 08 폐기 포함
]


def _goto(clock: FakeClock, target: datetime) -> None:
    clock.advance(seconds=int((target - clock.now()).total_seconds()))


def _at(day: date, t: time) -> datetime:
    return datetime.combine(day, t, tzinfo=KST).astimezone(UTC)


def _make_pack_mls(rng: random.Random, count: int, unknown_ratio: float) -> list[int | None]:
    return [None if rng.random() < unknown_ratio else rng.choice(ML_CHOICES) for _ in range(count)]


def _make_staff(db: Session, clock: FakeClock, username: str, name: str, role: Role):
    row, _ = staff_service.create_staff(db, clock, username=username, name=name, role=role)
    password = generate_temp_password()
    row.password_hash = hash_password(password)
    row.must_change_password = False  # 바로 쓸 수 있는 데모 계정
    return row, password


def _write_accounts(path: Path, passwords: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "모유 재고 관리 서버 - 데모 계정\n"
        "================================\n"
        "※ 시험용 가짜 자료입니다. 실제 환자 정보를 넣지 마세요.\n\n"
        "[직원 로그인]\n"
        f"  관리자    아이디: admin    비밀번호: {passwords['admin']}\n"
        f"  간호사    아이디: nurse1   비밀번호: {passwords['nurse1']}\n\n"
        "[보호자 로그인 예시]\n"
        "  전화번호: 010-1234-5601   아기 등록번호: DEMO-01\n"
        "  (DEMO-01~08 과 전화번호 010-1234-5601~5608 이 한 쌍씩 맞습니다)\n",
        encoding="utf-8",
    )


def _apply(db, clock, rng, patient_id, plan, slot, days_ago, actor) -> None:
    if slot == "in" and days_ago in plan.intake:
        n = plan.intake[days_ago]
        stock.add_record(
            db, clock, patient_id=patient_id, kind=RecordKind.INTAKE, count=n,
            staff=actor, pack_mls=_make_pack_mls(rng, n, plan.unknown_ratio),
        )
    elif slot in ("use", "use2"):
        total = plan.use_per_day if plan.use_until <= days_ago <= plan.use_from else 0
        # 하루 사용량을 두 번에 나눈다(오늘은 오전에 모두).
        if days_ago == 0:
            n = total if slot == "use" else 0
        else:
            n = (total + 1) // 2 if slot == "use" else total // 2
        n = min(n, stock.balance(db, patient_id))
        if n > 0:
            stock.add_record(
                db, clock, patient_id=patient_id, kind=RecordKind.USE, count=n, staff=actor
            )
    elif slot == "discard" and days_ago in plan.discard:
        n, reason = plan.discard[days_ago]
        n = min(n, stock.balance(db, patient_id))
        if n > 0:
            stock.add_record(
                db, clock, patient_id=patient_id, kind=RecordKind.DISCARD, count=n,
                staff=actor, discard_reason=reason,
            )


def _add_pending(db, clock, config, made) -> None:
    # 보호자가 제출한 것처럼: 보호자 세션 행을 만들어 제출 서비스로 넣고, 세션은 지운다.
    for patient, pack_mls in ((made[2], [80, 80, 100, None]), (made[4], [60, 100, None])):
        _, hashed = new_token()
        session = GuardianSession(
            token_hash=hashed, phone=patient.guardian_phone, station_id=None,
            created_at=clock.now(), last_seen_at=clock.now(),
        )
        db.add(session)
        db.flush()
        submissions.submit(
            db, clock, config, session=session, patient_id=patient.id,
            count=len(pack_mls), pack_mls=pack_mls,
        )
        db.delete(session)
        db.flush()


def build_demo(db_path: Path, accounts_path: Path, today: date | None = None) -> dict[str, str]:
    db_path, accounts_path = Path(db_path), Path(accounts_path)
    if db_path.exists():
        raise SystemExit(f"이미 파일이 있습니다: {db_path}")
    today = today or datetime.now(KST).date()
    rng = random.Random(20261115)
    clock = FakeClock(_at(today - timedelta(days=DAYS), time(7, 0)))
    config = Config(db_path=db_path)
    engine = make_engine(db_path)
    try:
        migrate(engine, db_path, clock.now())
        with Session(engine) as db:
            admin, admin_pw = _make_staff(db, clock, "admin", "데모 관리자", Role.ADMIN)
            nurse, nurse_pw = _make_staff(db, clock, "nurse1", "데모 간호사", Role.NURSE)
            made = [
                patients.create_patient(
                    db,
                    clock,
                    PatientCreate(
                        reg_no=f"DEMO-{i:02d}",
                        baby_name=f"데모 아기 {i}",
                        bed=str(i),
                        guardian_name=f"데모 보호자 {i}",
                        guardian_phone=f"010-1234-56{i:02d}",
                        admitted_on=today - timedelta(days=DAYS),
                    ),
                )
                for i in range(1, 9)
            ]
            for days_ago in range(DAYS, -1, -1):
                day = today - timedelta(days=days_ago)
                for slot, t in SLOTS:
                    if days_ago == 0 and slot == "use2":
                        continue
                    for idx, (patient, plan) in enumerate(zip(made, PLANS, strict=True)):
                        actor = nurse if idx % 2 == 0 else admin
                        _goto(clock, _at(day, t))
                        _apply(db, clock, rng, patient.id, plan, slot, days_ago, actor)
            _goto(clock, _at(today, time(9, 30)))
            _add_pending(db, clock, config, made)
            db.commit()
    except BaseException:
        engine.dispose()
        db_path.unlink(missing_ok=True)
        raise
    engine.dispose()
    passwords = {"admin": admin_pw, "nurse1": nurse_pw}
    _write_accounts(accounts_path, passwords)
    return passwords


def main(argv: list[str] | None = None) -> None:
    _utf8_streams()
    parser = argparse.ArgumentParser(description="데모 DB와 계정 파일을 만듭니다")
    parser.add_argument("db_path", type=Path)
    parser.add_argument("accounts_path", type=Path)
    parser.add_argument("--today", type=date.fromisoformat, default=None)
    args = parser.parse_args(argv)
    build_demo(args.db_path, args.accounts_path, args.today)
    print(f"데모 DB를 만들었습니다: {args.db_path}")


if __name__ == "__main__":
    main(sys.argv[1:])
