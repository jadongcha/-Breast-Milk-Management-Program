import argparse
import sys
from datetime import UTC, datetime

from milkbank.cli import _utf8_streams
from milkbank.config import load_config
from milkbank.tls import TlsError, setup_tls


def main() -> None:
    _utf8_streams()
    parser = argparse.ArgumentParser(description="서버 인증서와 서버 등록 QR을 만듭니다")
    parser.add_argument("--host", action="append", required=True)
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--new-key", action="store_true")
    args = parser.parse_args()

    tls_dir = load_config().tls_dir
    had_key = (tls_dir / "server.key").exists()
    try:
        result = setup_tls(tls_dir, args.host, args.port, args.new_key, datetime.now(UTC))
    except TlsError as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from None
    print(f"서버 주소: {result.url}")
    print(f"인증서 확인값: {result.pin}")
    print(f"서버 등록 QR: {result.qr_path.resolve()}")
    print("이 QR을 출력해 신생아실에 보관하세요")
    if args.new_key and had_key:
        print("인증서 키를 새로 만들었습니다. 모든 기기에서 서버 등록 QR을 다시 찍어야 합니다")
