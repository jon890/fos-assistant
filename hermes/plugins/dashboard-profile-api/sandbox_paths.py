"""실행 공간 정책의 경로 형식과 경로 겹침 검사다. 정책 읽기는 `sandbox.py` 가 한다."""

from __future__ import annotations

from typing import Optional
from .common import _skill_root


# 실행 공간이 직접 쓰는 컨테이너 경로다. 운영 마운트가 이 자리를 가리면 사용자 공간이 바뀐다.
SANDBOX_RESERVED_PATHS = ("/workspace", "/root")


def _sandbox_path_ok(value) -> bool:
    """`:` 없는 절대 경로이고 빈 조각과 `..` 이 없는지 본다."""
    if (not isinstance(value, str) or not value.startswith("/") or ":" in value
            or any(ord(ch) < 32 or ord(ch) == 127 for ch in value)):
        return False
    return all(part not in ("", ".", "..") for part in value.split("/")[1:])


def _sandbox_mount_ok(value) -> bool:
    """`<원본 절대 경로>:<컨테이너 절대 경로>` 하나를 본다. 컨테이너 경로가 `/workspace`, `/root` 자리면 틀리다."""
    if not isinstance(value, str):
        return False
    pieces = value.split(":")
    if len(pieces) != 2 or not all(_sandbox_path_ok(piece) for piece in pieces):
        return False
    target = pieces[1]
    # 경로 조각 기준으로 본다. `/rootfs` 는 `/root` 아래가 아니다.
    return not any(target == reserved or target.startswith(reserved + "/") for reserved in SANDBOX_RESERVED_PATHS)


def _sandbox_mounts_ok(value) -> bool:
    return isinstance(value, list) and all(_sandbox_mount_ok(entry) for entry in value)


def _sandbox_paths_overlap(first: str, second: str) -> bool:
    """두 절대 경로가 같거나 한쪽이 다른 쪽 아래인지 경로 조각 기준으로 본다."""
    first = first.rstrip("/") or "/"
    second = second.rstrip("/") or "/"

    def under(child, parent):
        return child == parent or parent == "/" or child.startswith(parent + "/")

    return under(first, second) or under(second, first)


def _sandbox_mount_overlaps(mount: str, workspace_root: str) -> bool:
    """마운트 원본이 `workspace_root` 와 겹치는지 경로 조각 기준으로 본다.

    겹치면 다른 사용자의 `/workspace` 가 읽기 전용 마운트로 함께 보인다.
    """
    source = mount.split(":")[0]
    return _sandbox_paths_overlap(source, workspace_root)


def _sandbox_attachment_mount_overlaps(mount: str, attachment_root: str, attachment_agent_root: str) -> bool:
    """운영 마운트가 첨부 원본이나 실행 공간 안의 첨부 경로 전체를 보이게 하는지 본다."""
    source, target = mount.split(":")
    return (_sandbox_paths_overlap(source, attachment_root)
            or _sandbox_paths_overlap(target, attachment_agent_root))


def _sandbox_attachment_roots_ok(attachment_root: str, attachment_agent_root: str, workspace_root: str) -> bool:
    """첨부 원본과 실행 공간 경로가 사용자 workspace 나 예약 경로와 겹치지 않는지 본다."""
    if _sandbox_paths_overlap(attachment_root, workspace_root):
        return False
    return not any(_sandbox_paths_overlap(attachment_agent_root, reserved)
                   for reserved in SANDBOX_RESERVED_PATHS)


def _sandbox_connector_output_root_ok(root: str, workspace_root: str, attachment_root: str,
                                      attachment_agent_root: str) -> bool:
    """커넥터 출력 루트가 사용자 workspace, 첨부 경로, 예약 경로와 겹치지 않는지 본다(ADR-20261008 connector-output-files)."""
    return not any(_sandbox_paths_overlap(root, other)
                   for other in (workspace_root, attachment_root, attachment_agent_root, *SANDBOX_RESERVED_PATHS))


def _sandbox_connector_output_mount_overlaps(mount: str, connector_output_root: Optional[str]) -> bool:
    """운영 마운트가 커넥터 출력 루트를 원본이나 대상으로 덮는지 본다. 덮으면 다른 profile 의 출력이 보인다."""
    if connector_output_root is None:
        return False
    source, target = mount.split(":")
    return (_sandbox_paths_overlap(source, connector_output_root)
            or _sandbox_paths_overlap(target, connector_output_root))


def _sandbox_skill_root_ok(skill_root: str, skill_agent_root: str, workspace_root: str, attachment_root: str,
                           attachment_agent_root: str, connector_output_root: Optional[str]) -> bool:
    """스킬 루트의 원본과 Hermes 쪽 대상이 사용자 workspace, 첨부, 커넥터 출력, 예약 경로와 겹치지 않는지 본다.

    원본이 겹치면 스킬 마운트가 다른 사용자의 파일을 보이게 하고, 대상이 겹치면 다른 마운트의 자리를 가린다.
    """
    sources = (workspace_root, attachment_root) + ((connector_output_root,) if connector_output_root else ())
    targets = (*SANDBOX_RESERVED_PATHS, attachment_agent_root) + (
        (connector_output_root,) if connector_output_root else ())
    return not (any(_sandbox_paths_overlap(skill_root, other) for other in sources)
                or any(_sandbox_paths_overlap(skill_agent_root, other) for other in targets))


def _sandbox_skill_mount_overlaps(mount: str, skill_root: Optional[str]) -> bool:
    """운영 마운트가 스킬 루트를 원본으로 덮거나 Hermes 쪽 스킬 루트를 대상으로 덮는지 본다.

    덮으면 다른 profile 의 스킬이 보이거나 스킬 마운트 자리가 가려진다. 정책에 `skill_root` 가 없으면 보지 않는다.
    """
    if skill_root is None:
        return False
    skill_agent_root = _skill_root()
    if skill_agent_root is None:
        return True
    source, target = mount.split(":")
    return (_sandbox_paths_overlap(source, skill_root)
            or _sandbox_paths_overlap(target, str(skill_agent_root)))
