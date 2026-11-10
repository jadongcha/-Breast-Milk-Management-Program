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
    assert f"서버 주소: {result.url}" in capsys.readouterr().out
    assert json.loads((tls_dir / "server.json").read_text(encoding="utf-8"))["port"] == 9443
