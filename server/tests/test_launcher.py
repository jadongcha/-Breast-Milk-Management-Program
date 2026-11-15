import sys
from importlib.metadata import version

import pytest

from milkbank import cli, launcher, serve, setup_tls


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
