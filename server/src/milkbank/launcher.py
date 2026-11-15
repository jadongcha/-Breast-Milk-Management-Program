"""다른 PC용 실행 파일(milkbank-server)의 하위 명령 진입점."""
import sys
from importlib.metadata import PackageNotFoundError, version

from . import cli, serve, setup_tls
from .cli import _utf8_streams

_USAGE = """사용법: milkbank-server <명령> [옵션]

명령:
  setup-tls     서버 인증서와 서버 등록 QR을 만듭니다 (--host <IP> [--port 8443])
  create-admin  첫 관리자 계정을 만듭니다 (--username <아이디> --name <이름>)
  serve         서버를 시작합니다
  version       프로그램 버전을 보여 줍니다
"""


def _version() -> None:
    try:
        print(version("milkbank"))
    except PackageNotFoundError:
        print("알 수 없음")


def main(argv: list[str] | None = None) -> None:
    _utf8_streams()
    args = list(sys.argv[1:] if argv is None else argv)
    command = args[0] if args else None
    rest = args[1:]
    if command == "version":
        _version()
        return
    # 모듈 속성으로 찾아 부른다(기존 명령과 같은 main()).
    entry = {"setup-tls": setup_tls, "create-admin": cli, "serve": serve}.get(command or "")
    if entry is None:
        print(_USAGE, file=sys.stderr, end="")
        raise SystemExit(2)
    sys.argv = [f"milkbank-server {command}", *rest]
    entry.main()
