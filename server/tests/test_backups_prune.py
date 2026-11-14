from datetime import UTC, datetime, timedelta

from milkbank.backups import prune_backups
from milkbank.clock import FakeClock


def _touch(d, name):
    p = d / name
    p.write_bytes(b"x")
    return p


def test_prune_deletes_only_old_matching_backups(tmp_path):
    now = datetime(2026, 11, 10, 0, 0, tzinfo=UTC)
    old = _touch(tmp_path, f"milkbank-{now - timedelta(days=31):%Y%m%dT%H%M%SZ}-before-0004.db")
    recent = _touch(tmp_path, f"milkbank-{now - timedelta(days=29):%Y%m%dT%H%M%SZ}-before-0004.db")
    other = _touch(tmp_path, "notes.txt")
    bad = _touch(tmp_path, "milkbank-20200101-before-0004.db")
    gone = prune_backups(tmp_path, FakeClock(now), 30)
    assert gone == [old]
    assert not old.exists()
    assert recent.exists() and other.exists() and bad.exists()


def test_prune_missing_dir_is_noop(tmp_path):
    now = datetime(2026, 11, 10, tzinfo=UTC)
    assert prune_backups(tmp_path / "none", FakeClock(now), 30) == []


def test_prune_failure_does_not_stop_pass(tmp_path, monkeypatch):
    now = datetime(2026, 11, 10, tzinfo=UTC)
    a = _touch(tmp_path, "milkbank-20200101T000000Z-before-0004.db")
    b = _touch(tmp_path, "milkbank-20200102T000000Z-before-0004.db")
    real = type(a).unlink

    def flaky(self, *args, **kw):
        if self.name == a.name:
            raise PermissionError("locked")
        return real(self, *args, **kw)

    monkeypatch.setattr(type(a), "unlink", flaky)
    gone = prune_backups(tmp_path, FakeClock(now), 30)
    assert gone == [b] and a.exists() and not b.exists()


def test_erase_pass_prunes_backups(app, tmp_path):
    from milkbank.main import _erase_due_once

    d = app.state.config.backups_dir
    d.mkdir(parents=True, exist_ok=True)
    old = _touch(d, "milkbank-20200101T000000Z-before-0004.db")
    _erase_due_once(app)
    assert not old.exists()
