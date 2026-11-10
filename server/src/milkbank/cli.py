import argparse
import sys

from . import staff as staff_service
from .clock import SystemClock
from .config import load_config
from .db import init_db, make_engine, make_session_factory
from .errors import DomainError
from .models import Role


def _utf8_streams() -> None:
    # Windows에서 출력을 리디렉션해도 한글이 깨지지 않게 한다.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")


def main() -> None:
    _utf8_streams()
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
    except DomainError as e:
        print(e.message, file=sys.stderr)
        raise SystemExit(1) from None
    finally:
        engine.dispose()
    print("임시 비밀번호: (다음 줄)")
    print(temp)
