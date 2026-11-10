import argparse

from . import staff as staff_service
from .clock import SystemClock
from .config import load_config
from .db import init_db, make_engine, make_session_factory
from .models import Role


def main() -> None:
    parser = argparse.ArgumentParser(description="첫 관리자 계정을 만듭니다")
    parser.add_argument("--username", required=True)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()

    engine = make_engine(load_config().db_path)
    try:
        init_db(engine)
        with make_session_factory(engine)() as db:
            _, temp = staff_service.create_staff(
                db, SystemClock(), username=args.username, name=args.name, role=Role.ADMIN
            )
            db.commit()
    finally:
        engine.dispose()
    print(f"임시 비밀번호: {temp}")
