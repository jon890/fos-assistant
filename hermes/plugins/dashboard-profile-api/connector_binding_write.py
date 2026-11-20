"""바인딩 설치가 profile 파일을 백업하고 원자적으로 바꾸며, 실패하면 이 요청이 쓴 파일만 되돌리는 쓰기다(`connector_binding.py` 에서 옮겼다)."""

from __future__ import annotations

import os
import pathlib
import time

from .common import _atomic_private_write
from .connector_state import POLICY_PLUGIN, _restore_binding_files


def _write_binding_files(profile_dir: pathlib.Path, originals: dict, targets: dict, plugin_values: dict,
                         skills_dir: pathlib.Path, backup_paths: tuple) -> None:
    """`targets` 를 쓴다. 예외가 나면 이 요청이 쓴 파일과 만든 디렉터리를 되돌리고 다시 던진다."""
    backup = profile_dir / "connector-backups" / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    for path in backup_paths:
        # `.env` 와 스킬 파일은 뜨지 않는다. `.env` 에는 사용자의 비밀 원문이 있다.
        if originals[path] is not None:
            _atomic_private_write(backup / path.name, originals[path])
    for path, value in plugin_values.items():
        if originals[path] is not None and originals[path] != value:
            _atomic_private_write(backup / ("%s.%s" % (POLICY_PLUGIN, path.name)), originals[path])

    def shared(path):
        # 스킬과 plugin 파일은 gateway 가 읽는 파일이다. 새 profile 에 plugin 을 복사할 때와 같은 644 로 둔다.
        return path in plugin_values or skills_dir in path.parents

    written = []
    created_dirs = []
    try:
        if any((path.read_bytes() if path.exists() else None) != value for path, value in originals.items()):
            raise FileExistsError("저장 전 profile 설정이 밖에서 바뀌었다")
        for path, value in targets.items():
            current = path.read_bytes() if path.exists() else None
            if current != originals[path]:
                raise FileExistsError("profile 설정이 밖에서 바뀌었다")
            if current == value:
                continue
            # 교체 직후 예외가 나도 복원 후보에 남긴다. 실제 값은 복원할 때 다시 견준다.
            written.append(path)
            if value is None:
                path.unlink()
            else:
                for directory in reversed(path.parents):
                    if directory in profile_dir.parents or directory == profile_dir or directory.is_dir():
                        continue
                    directory.mkdir()
                    created_dirs.append(directory)
                    os.chmod(directory, 0o755)
                _atomic_private_write(path, value)
                if shared(path):
                    os.chmod(path, 0o644)
    except Exception:
        _restore_binding_files(written, originals, targets, shared)
        for directory in reversed(created_dirs):
            # 이 요청이 만든 디렉터리만 지운다. 그 사이 밖에서 무엇이 생겼으면 비어 있지 않아 남는다.
            try:
                directory.rmdir()
            except OSError:
                pass
        raise
