"""마이그레이션 백업 정리. 백업에는 개인정보가 들어 있으므로 보관 기간이 지나면 지운다."""
import logging
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path

from .clock import Clock

log = logging.getLogger(__name__)

_PATTERN = re.compile(r"^milkbank-(\d{8}T\d{6}Z)-before-[0-9A-Za-z_]+\.db$")


def prune_backups(backups_dir: Path, clock: Clock, retention_days: int) -> list[Path]:
    if not backups_dir.is_dir():
        return []
    cutoff = clock.now() - timedelta(days=retention_days)
    deleted: list[Path] = []
    for path in sorted(backups_dir.iterdir()):
        m = _PATTERN.match(path.name)
        if m is None:
            continue
        try:
            stamp = datetime.strptime(m.group(1), "%Y%m%dT%H%M%SZ").replace(tzinfo=UTC)
        except ValueError:
            continue
        if stamp >= cutoff:
            continue
        try:
            path.unlink()
        except OSError:
            log.exception("오래된 백업 %s를 지우지 못했습니다", path.name)
            continue
        log.info("보관 기간이 지난 백업 %s를 지웠습니다", path.name)
        deleted.append(path)
    return deleted
