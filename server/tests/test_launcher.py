import os
import sys
from importlib.metadata import version

import pytest

from milkbank import cli, launcher, serve, setup_tls


@pytest.fixture(autouse=True)
def _restore_argv(monkeypatch):
    # launcher.main 이 sys.argv 를 덮어쓰므로, 테스트가 끝나면 원래대로 돌려놓는다.
    monkeypatch.setattr(sys, "argv", list(sys.argv))


@pytest.mark.parametrize(
    ("command", "module"),
    [("setup-tls", setup_tls), ("create-admin", cli), ("serve", serve)],
)
def test_command_calls_existing_main_with_rest_args(monkeypatch, command, module):
    seen = []
    monkeypatch.setattr(module, "main", lambda: seen.append(list(sys.argv[1:])))
    launcher.main([command, "--host", "10.0.0.5", "--port", "9000"])
    assert seen == [["--host", "10.0.0.5", "--port", "9000"]]


def test_command_without_extra_args(monkeypatch):
    seen = []
    monkeypatch.setattr(serve, "main", lambda: seen.append(list(sys.argv[1:])))
    launcher.main(["serve"])
    assert seen == [[]]


def test_only_selected_command_runs(monkeypatch):
    seen = []
    monkeypatch.setattr(serve, "main", lambda: seen.append("serve"))
    monkeypatch.setattr(cli, "main", lambda: seen.append("cli"))
    monkeypatch.setattr(setup_tls, "main", lambda: seen.append("tls"))
    launcher.main(["create-admin", "--username", "a", "--name", "b"])
    assert seen == ["cli"]


@pytest.mark.parametrize("argv", [[], ["nope"], ["nope", "serve"]])
def test_unknown_or_missing_command_prints_usage_and_exits_2(capsys, argv):
    with pytest.raises(SystemExit) as e:
        launcher.main(argv)
    assert e.value.code == 2
    err = capsys.readouterr().err
    for name in ("setup-tls", "create-admin", "serve", "version"):
        assert name in err
    assert "사용법" in err


def test_version_prints_package_version(capsys):
    launcher.main(["version"])
    assert capsys.readouterr().out.strip() == version("milkbank")


def test_frozen_launcher_sets_packaged_setup_hint(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.delenv("MILKBANK_SETUP_HINT", raising=False)
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    try:
        with pytest.raises(SystemExit) as e:
            launcher.main(["serve"])
    finally:
        os.environ.pop("MILKBANK_SETUP_HINT", None)  # launcher가 직접 넣은 값이라 직접 지운다
    assert e.value.code == 1
    err = capsys.readouterr().err
    assert "먼저 처음설정.bat를 실행하세요" in err
    assert "milkbank-setup-tls" not in err


def test_unfrozen_launcher_keeps_developer_message(monkeypatch, tmp_path, capsys):
    monkeypatch.delenv("MILKBANK_SETUP_HINT", raising=False)
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    with pytest.raises(SystemExit):
        launcher.main(["serve"])
    assert "먼저 milkbank-setup-tls를 실행하세요" in capsys.readouterr().err
