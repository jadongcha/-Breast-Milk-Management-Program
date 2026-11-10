import json
import sys

import uvicorn

from milkbank.cli import _utf8_streams
from milkbank.clock import SystemClock
from milkbank.config import load_config
from milkbank.db import make_engine
from milkbank.migrate import LegacyDatabaseError, UnknownRevisionError, migrate

_MISSING = "먼저 milkbank-setup-tls를 실행하세요"


def main() -> None:
    _utf8_streams()
    config = load_config()
    tls_dir = config.tls_dir
    crt = tls_dir / "server.crt"
    key = tls_dir / "server.key"
    try:
        info = json.loads((tls_dir / "server.json").read_text(encoding="utf-8"))
        port = int(info["port"])
        url = str(info["url"])
    except (OSError, ValueError, KeyError, TypeError):
        print(_MISSING, file=sys.stderr)
        raise SystemExit(1) from None
    if not (crt.is_file() and key.is_file()):
        print(_MISSING, file=sys.stderr)
        raise SystemExit(1)
    engine = make_engine(config.db_path)
    try:
        migrate(engine, config.db_path, SystemClock().now())
    except (LegacyDatabaseError, UnknownRevisionError) as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1) from None
    finally:
        engine.dispose()
    print(f"서버 주소: {url}", flush=True)
    uvicorn.run(
        "milkbank.main:app_factory",
        factory=True,
        host="0.0.0.0",
        port=port,
        ssl_certfile=str(crt),
        ssl_keyfile=str(key),
        ssl_ciphers="ECDHE+AESGCM:ECDHE+CHACHA20",
    )
