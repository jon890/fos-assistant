from __future__ import annotations

import os
import pathlib
import shutil
from typing import Optional
from .common import (
    logger,
)

from .sandbox import (
    _sandbox_connector_output_profile_directory,
    _sandbox_policy,
)


# 커넥터가 계산할 목록을 파일로 쓰는 출력 디렉터리를 만들고 지운다(ADR-20261008 connector-output-files).
# 실행 공간에 붙이는 경로는 `sandbox.py` 의 `_sandbox_connector_output_profile_directory` 가 정한다.


def _sandbox_make_private_directory(directory: str) -> None:
    """디렉터리를 만들고 실제 경로가 그 경로 그대로인지 본다. 링크가 섞였으면 거절한다."""
    os.makedirs(directory, mode=0o700, exist_ok=True)
    path = pathlib.Path(directory)
    if path.resolve(strict=True) != path or not path.is_dir():
        raise OSError("connector output directory resolves elsewhere")


def _sandbox_prepare_connector_output(policy: dict, profile: str, owner: str) -> None:
    """셸 설정이 붙일 커넥터 출력 디렉터리를 미리 만든다. 없으면 Docker 가 root 소유로 만들어 커넥터가 쓰지 못한다."""
    directory = _sandbox_connector_output_profile_directory(policy, profile, owner)
    if directory is not None:
        _sandbox_make_private_directory(directory)


def _sandbox_connector_output_directory(policy: dict, profile: str, owner: str, connector: str) -> Optional[str]:
    """바인딩 설치가 커넥터의 `owner_output_env` 에 넣을 디렉터리를 만들어 돌려준다. 정책에 키가 없으면 None 이다."""
    directory = _sandbox_connector_output_profile_directory(policy, profile, owner)
    if directory is None:
        return None
    _sandbox_make_private_directory(directory)
    target = "%s/%s" % (directory, connector)
    _sandbox_make_private_directory(target)
    return target


def _sandbox_remove_connector_output(value: str) -> None:
    """뗀 바인딩의 커넥터 출력 디렉터리를 지운다.

    지금 정책의 `connector_output_root` 아래이고 실제 경로가 그 경로 그대로일 때만 지운다. 아니면 남긴다.
    """
    policy = _sandbox_policy()
    root = policy.get("connector_output_root") if policy is not None else None
    if root is None or not value.startswith(root.rstrip("/") + "/users/"):
        return
    path = pathlib.Path(value)
    try:
        if path.is_symlink() or not path.is_dir() or path.resolve(strict=True) != path:
            return
        shutil.rmtree(path)
    except OSError as error:
        logger.warning("dashboard-profile-api: 뗀 커넥터의 출력 디렉터리를 지우지 못했다: %s", type(error).__name__)
