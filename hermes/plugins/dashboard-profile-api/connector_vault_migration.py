from __future__ import annotations

import re

from .common import (
    logger,
)

from .connector_vault import (
    VAULT_ID_RE,
    VAULT_TEMP_PREFIX,
    _durable_private_write,
    _parse_vault,
    _read_vault,
    _vault_bytes,
    _vault_dir,
)

from .connector_vault_key import (
    _vault_key_file_shared,
    _vault_keys,
)


# 이관이 보는 파일 이름이다. 쓰기 중의 임시 파일(`.connector-`)과 다른 이름은 건너뛴다.
VAULT_FILE_RE = re.compile(r"^(c[1-9][0-9]{0,18})\.json$")
# 기동 로그에 남기는 수의 이름이다. 운영은 배포 뒤 `plain` 과 `old_key` 가 0 인지 본다(REQ3).
# `kept` 는 손대지 않은 활성 key 의 파일이고, key 가 준비되지 않았을 때는 암호화한 파일 모두다.
MIGRATION_COUNTS = ("kept", "from_plain", "from_old_key", "plain", "old_key", "unopened", "temp_removed")


def _migrate_vault_files() -> dict:
    """기동 때 보관 파일을 활성 key 로 다시 쓴다(ADR-20261103 connector-vault-key).

    v1 평문 파일과 옛 key 의 v2 파일을 활성 key 의 v2 로 바꾼다. 다시 쓴 파일은 다시 읽어 값이 같은지 보고,
    다르면 원래 바이트로 되돌린다. 실패한 파일은 건드리지 않고 이름만 로그에 남긴다. 예외를 밖으로 내지 않는다.
    값과 key, 경로는 로그에 싣지 않는다. 남은 수를 담은 dict 를 돌려준다.
    """
    counts = dict.fromkeys(MIGRATION_COUNTS, 0)
    left = []
    keys = _vault_keys()
    if keys.required and not keys.ready:
        logger.error("dashboard-profile-api: key 필수인데 보관 파일 key 가 %s 다. 보관 파일 쓰기를 거절한다", keys.state)
    if keys.ready and _vault_key_file_shared():
        logger.warning("dashboard-profile-api: 보관 파일 key 파일을 소유자가 아닌 사용자도 읽을 수 있다. 소유자만 읽게 권한을 줄인다")
    directory = _vault_dir()
    if directory.is_symlink():
        logger.warning("dashboard-profile-api: 보관 파일 디렉터리가 링크라 이관하지 않는다")
        return counts
    names = sorted(entry.name for entry in directory.iterdir()) if directory.is_dir() else []
    for name in names:
        if name.startswith(VAULT_TEMP_PREFIX):
            # 쓰는 중에 프로세스가 죽어 남은 임시 파일이다. 평문일 수 있다. 기동 중에는 쓰는 쪽이 없다.
            try:
                (directory / name).unlink()
                counts["temp_removed"] += 1
            except OSError as error:
                logger.warning("dashboard-profile-api: 보관 파일 임시 파일을 지우지 못했다: %s", type(error).__name__)
            continue
        match = VAULT_FILE_RE.match(name)
        if match is None or not VAULT_ID_RE.match(match.group(1)):
            continue
        vault = match.group(1)
        try:
            outcome = _migrate_vault_file(vault, keys)
        except Exception as error:
            logger.warning("dashboard-profile-api: 보관 파일 %s 를 이관하지 못했다: %s", vault, type(error).__name__)
            outcome = "plain"
        counts[outcome] += 1
        if outcome in ("plain", "old_key", "unopened"):
            left.append(vault)
    logger.info("dashboard-profile-api: 보관 파일 이관: key=%s 필수=%s, 그대로 둔 암호화 파일 %d, v1 에서 옮김 %d, 옛 key 에서 옮김 %d, "
                "남은 평문 파일 %d, 남은 옛 key 파일 %d, 풀지 못한 파일 %d, 지운 임시 파일 %d",
                keys.state, "참" if keys.required else "거짓", counts["kept"], counts["from_plain"], counts["from_old_key"],
                counts["plain"], counts["old_key"], counts["unopened"], counts["temp_removed"])
    if left:
        logger.warning("dashboard-profile-api: 이관하지 못하고 남은 보관 파일: %s", ", ".join(left))
    return counts


def _migrate_vault_file(vault: str, keys) -> str:
    """보관 파일 하나를 분류하고 필요하면 다시 쓴다. 결과는 `MIGRATION_COUNTS` 의 이름 하나다."""
    path = _vault_dir() / ("%s.json" % vault)
    if path.is_symlink() or not path.is_file():
        return "plain"
    original = path.read_bytes()
    try:
        stored = _parse_vault(original)
    except ValueError:
        # 깨진 파일은 건드리지 않고 평문 파일로 센다(스펙 DECISION5).
        return "plain"
    if not keys.ready:
        # key 가 준비되지 않으면 다시 쓰지 않고 세기만 한다. 평문으로 바꿔 쓰는 일이 없게 한다.
        return "plain" if stored["v"] == 1 else "kept"
    if stored["v"] == 1:
        values = stored["values"]
        moved = "from_plain"
    else:
        old = stored["key_id"] != keys.active_id
        try:
            values = _read_vault(vault, keys)["values"]
        except ValueError:
            return "old_key" if old else "unopened"
        if not old:
            return "kept"
        moved = "from_old_key"
    _durable_private_write(path, _vault_bytes(vault, stored["connector"], values, keys))
    try:
        written = _read_vault(vault, keys)
        same = written["connector"] == stored["connector"] and written["values"] == values and written["v"] == 2
    except ValueError:
        same = False
    if same:
        return moved
    _durable_private_write(path, original)
    return "plain" if stored["v"] == 1 else "old_key"
