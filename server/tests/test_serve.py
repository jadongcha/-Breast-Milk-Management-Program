import json
from datetime import UTC, datetime

import pytest

from milkbank import serve
from milkbank.tls import setup_tls

NOW = datetime(2026, 11, 10, tzinfo=UTC)


def test_serve_without_tls_exits(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    with pytest.raises(SystemExit) as e:
        serve.main()
    assert e.value.code == 1
    assert "먼저 milkbank-setup-tls를 실행하세요" in capsys.readouterr().err


@pytest.mark.parametrize("content", ["not json", "{}", '{"port": "x", "url": 1}'])
def test_serve_invalid_server_json_exits(tmp_path, monkeypatch, capsys, content):
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    tls_dir = tmp_path / "tls"
    setup_tls(tls_dir, ["127.0.0.1"], 9443, False, NOW)
    (tls_dir / "server.json").write_text(content, encoding="utf-8")
    with pytest.raises(SystemExit) as e:
        serve.main()
    assert e.value.code == 1
    assert "먼저 milkbank-setup-tls를 실행하세요" in capsys.readouterr().err


def test_serve_runs_uvicorn_with_tls(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    tls_dir = tmp_path / "tls"
    result = setup_tls(tls_dir, ["127.0.0.1"], 9443, False, NOW)
    calls = []
    monkeypatch.setattr(serve.uvicorn, "run", lambda *a, **kw: calls.append((a, kw)))
    serve.main()
    (args, kwargs), = calls
    assert args == ("milkbank.main:app_factory",)
    assert kwargs["factory"] is True
    assert kwargs["host"] == "0.0.0.0"
    assert kwargs["port"] == 9443
    assert kwargs["ssl_certfile"] == str(tls_dir / "server.crt")
    assert kwargs["ssl_keyfile"] == str(tls_dir / "server.key")
    assert kwargs["ssl_ciphers"] == "ECDHE+AESGCM:ECDHE+CHACHA20"
    assert f"서버 주소: {result.url}" in capsys.readouterr().out
    assert json.loads((tls_dir / "server.json").read_text(encoding="utf-8"))["port"] == 9443


def test_serve_migrates_before_running(tmp_path, monkeypatch):
    db_path = tmp_path / "m.db"
    monkeypatch.setenv("MILKBANK_DB", str(db_path))
    setup_tls(tmp_path / "tls", ["127.0.0.1"], 9443, False, NOW)
    monkeypatch.setattr(serve.uvicorn, "run", lambda *a, **kw: None)
    serve.main()
    import sqlite3
    from contextlib import closing

    with closing(sqlite3.connect(db_path)) as c:
        assert c.execute("SELECT version_num FROM alembic_version").fetchone()[0] == "0004"


def test_serve_legacy_db_exits(tmp_path, monkeypatch, capsys):
    from milkbank.db import make_engine
    from milkbank.models import Base

    db_path = tmp_path / "m.db"
    eng = make_engine(db_path)
    Base.metadata.create_all(eng)
    eng.dispose()
    monkeypatch.setenv("MILKBANK_DB", str(db_path))
    setup_tls(tmp_path / "tls", ["127.0.0.1"], 9443, False, NOW)
    called = []
    monkeypatch.setattr(serve.uvicorn, "run", lambda *a, **kw: called.append(1))
    with pytest.raises(SystemExit) as e:
        serve.main()
    assert e.value.code == 1
    assert "1단계 테스트용 DB는 지원하지 않습니다" in capsys.readouterr().err
    assert not called
