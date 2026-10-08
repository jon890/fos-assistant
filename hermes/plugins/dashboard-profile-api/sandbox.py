from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
from typing import Optional
from urllib.parse import urlsplit
from .common import (
    PROFILE_NAME_RE,
    logger,
)


# 셸과 파일 도구를 돌릴 docker 실행 공간 설정 JSON 이다. 모양은 `hermes/README.md` 의 「셸 실행 공간」 이 갖는다(ADR-086).
SANDBOX_ENV = "FOS_ASSISTANT_SANDBOX"
# 사진과 영상 파일을 Hermes host에서 직접 읽으면 다른 사용자의 첨부가 보일 수 있다. 실행 공간에서만 연다.
IMAGE_FILE_TOOLSETS = frozenset({"vision", "image_gen", "video_gen"})
# 켜면 profile 의 `terminal:` 을 실행 공간 설정으로 바꿔야 하는 도구다. 설정이 없으면 켜지 않는다.
SANDBOX_TOOLSETS = frozenset({"terminal", "file", "code_execution"}) | IMAGE_FILE_TOOLSETS
# 실행 공간 사용자 디렉터리 이름이다. Control Plane 이 사용자마다 정해 보낸다.
SANDBOX_OWNER_RE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
SANDBOX_NETWORK_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
# 실행 공간이 직접 쓰는 컨테이너 경로다. 운영 마운트가 이 자리를 가리면 사용자 공간이 바뀐다.
SANDBOX_RESERVED_PATHS = ("/workspace", "/root")
# hermes/README.md 의 「셸 실행 공간」 계약 표에 있는 최상위 키다. 그 밖의 키가 있으면 정책 전체를 틀린 것으로 본다.
SANDBOX_POLICY_KEYS = frozenset({
    "image", "workspace_root", "attachment_root", "attachment_agent_root", "connector_output_root", "network",
    "cpu", "memory_mb", "read_only_mounts", "profiles",
})
SANDBOX_PROFILE_KEYS = frozenset({"read_only_mounts", "env", "network"})
# 비밀값은 넣지 않는다. 운영 정책이 경로와 Backend 주소만 명시한다.
SANDBOX_PATH_ENV = frozenset({
    "CAREER_BACKEND_TOKEN_FILE", "CLAUDE_PLUGIN_ROOT", "CAREER_EVIDENCE_DIR",
    "CAREER_WORKSPACE_ROOT", "CAREER_DART_API_KEY_FILE",
})


def _sandbox_unavailable():
    from starlette.responses import JSONResponse

    return JSONResponse({"detail": "실행 공간이 설정되지 않았다", "code": "sandbox_unavailable"}, status_code=409)


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


def _sandbox_env_ok(value) -> bool:
    """운영 정책의 환경 값은 허용한 경로와 인증정보 없는 URL 만 받는다."""
    if not isinstance(value, dict):
        return False
    for name, entry in value.items():
        if name in SANDBOX_PATH_ENV:
            if not _sandbox_path_ok(entry):
                return False
        elif name == "CAREER_BACKEND_URL":
            if (not isinstance(entry, str) or not entry
                    or any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in entry)):
                return False
            try:
                parsed = urlsplit(entry)
                port = parsed.port
            except ValueError:
                return False
            if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                    or parsed.username is not None or parsed.password is not None
                    or parsed.query or parsed.fragment or (port is not None and port <= 0)):
                return False
        else:
            return False
    return True


def _sandbox_profiles(value, workspace_root: str, attachment_root: str, attachment_agent_root: str,
                      default_network, connector_output_root: Optional[str] = None) -> Optional[dict]:
    """정책에 등록된 profile 만 검증한다. 빈 목록은 모두 기존 실행을 유지한다."""
    if not isinstance(value, dict):
        return None
    profiles = {}
    for name, settings in value.items():
        if (not isinstance(name, str) or name == "default" or not PROFILE_NAME_RE.fullmatch(name)
                or not isinstance(settings, dict) or set(settings) - SANDBOX_PROFILE_KEYS):
            return None
        mounts = settings.get("read_only_mounts", [])
        if (not _sandbox_mounts_ok(mounts)
                or any(_sandbox_mount_overlaps(mount, workspace_root)
                       or _sandbox_attachment_mount_overlaps(mount, attachment_root, attachment_agent_root)
                       or _sandbox_connector_output_mount_overlaps(mount, connector_output_root)
                       for mount in mounts)):
            return None
        env = settings.get("env", {})
        if not _sandbox_env_ok(env):
            return None
        network = settings.get("network", default_network)
        if network is not None and not (isinstance(network, str) and SANDBOX_NETWORK_RE.fullmatch(network)):
            return None
        profiles[name] = {"read_only_mounts": list(mounts), "env": dict(env), "network": network}
    return profiles


def _sandbox_policy() -> Optional[dict]:
    """`SANDBOX_ENV` 의 JSON 을 읽어 검증한다. 없거나 하나라도 틀리면 None 이다.

    None 이면 셸과 파일 도구 저장을 거절한다. 유효한 정책의 profiles 에 없는 profile 은 기존 실행을 유지한다(ADR-086).
    """
    raw = os.environ.get(SANDBOX_ENV, "").strip()
    if not raw:
        logger.error("dashboard-profile-api: %s 가 없다", SANDBOX_ENV)
        return None
    try:
        value = json.loads(raw)
    except ValueError:
        logger.error("dashboard-profile-api: %s 가 JSON 이 아니다", SANDBOX_ENV)
        return None
    if not isinstance(value, dict):
        logger.error("dashboard-profile-api: %s 가 JSON object 가 아니다", SANDBOX_ENV)
        return None

    def invalid(key):
        logger.error("dashboard-profile-api: %s 의 %s 가 올바르지 않다", SANDBOX_ENV, key)
        return None

    unknown = sorted(set(value) - SANDBOX_POLICY_KEYS)
    if unknown:
        # 계약에 없는 키를 조용히 버리면 운영자가 걸었다고 믿는 제한이 빠진 채로 돈다.
        logger.error("dashboard-profile-api: %s 에 계약에 없는 키가 있다 %s", SANDBOX_ENV, unknown)
        return None

    image = value.get("image")
    if (not isinstance(image, str) or not image
            or any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in image)):
        return invalid("image")
    if not _sandbox_path_ok(value.get("workspace_root")):
        return invalid("workspace_root")
    if not _sandbox_path_ok(value.get("attachment_root")):
        return invalid("attachment_root")
    if not _sandbox_path_ok(value.get("attachment_agent_root")):
        return invalid("attachment_agent_root")
    network = value.get("network")
    if network is not None and not (isinstance(network, str) and SANDBOX_NETWORK_RE.fullmatch(network)):
        return invalid("network")
    cpu = value.get("cpu", 1)
    if isinstance(cpu, bool) or not isinstance(cpu, (int, float)) or not 0 < cpu <= 8:
        return invalid("cpu")
    memory_mb = value.get("memory_mb", 1024)
    if isinstance(memory_mb, bool) or not isinstance(memory_mb, int) or not 256 <= memory_mb <= 16384:
        return invalid("memory_mb")
    read_only_mounts = value.get("read_only_mounts", [])
    if not _sandbox_mounts_ok(read_only_mounts):
        return invalid("read_only_mounts")
    workspace_root = value["workspace_root"]
    attachment_root = value["attachment_root"]
    attachment_agent_root = value["attachment_agent_root"]
    if not _sandbox_attachment_roots_ok(attachment_root, attachment_agent_root, workspace_root):
        return invalid("attachment_root 또는 attachment_agent_root")
    # 선택 키다. 없으면 커넥터 출력 디렉터리를 붙이지 않고 셸 설정과 지문이 그대로다.
    connector_output_root = value.get("connector_output_root")
    if connector_output_root is not None and (
            not _sandbox_path_ok(connector_output_root)
            or not _sandbox_connector_output_root_ok(connector_output_root, workspace_root, attachment_root,
                                                     attachment_agent_root)):
        return invalid("connector_output_root")
    if any(_sandbox_mount_overlaps(mount, workspace_root)
           or _sandbox_attachment_mount_overlaps(mount, attachment_root, attachment_agent_root)
           or _sandbox_connector_output_mount_overlaps(mount, connector_output_root)
           for mount in read_only_mounts):
        return invalid("read_only_mounts")
    profiles = _sandbox_profiles(value.get("profiles"), workspace_root, attachment_root,
                                 attachment_agent_root, network, connector_output_root)
    if profiles is None:
        return invalid("profiles")
    return {
        "image": image,
        "workspace_root": value["workspace_root"],
        "attachment_root": attachment_root,
        "attachment_agent_root": attachment_agent_root,
        "connector_output_root": connector_output_root,
        "network": network,
        "cpu": cpu,
        "memory_mb": memory_mb,
        "read_only_mounts": list(read_only_mounts),
        "profiles": profiles,
    }


def _sandbox_workspace(policy: dict, owner: str) -> str:
    return "%s/%s" % (policy["workspace_root"].rstrip("/"), owner)


def _sandbox_attachment_key(owner: str) -> str:
    """실행 주인을 디스크 경로에 드러내지 않는 안정된 사용자 디렉터리 이름이다."""
    return hashlib.sha256(owner.encode("utf-8")).hexdigest()


def _sandbox_attachment_directory(policy: dict, owner: str) -> str:
    return "%s/users/%s" % (policy["attachment_root"].rstrip("/"), _sandbox_attachment_key(owner))


def _sandbox_attachment_agent_directory(policy: dict, owner: str) -> str:
    return "%s/users/%s" % (policy["attachment_agent_root"].rstrip("/"), _sandbox_attachment_key(owner))


def _sandbox_connector_output_profile_directory(policy: dict, profile: str, owner: str) -> Optional[str]:
    """그 profile 의 커넥터 출력 디렉터리다. 실행 공간에 같은 경로로 읽기 전용으로 붙는다. 정책에 키가 없으면 None 이다."""
    root = policy.get("connector_output_root")
    if root is None:
        return None
    return "%s/users/%s/%s" % (root.rstrip("/"), _sandbox_attachment_key(owner), profile)


class SandboxAttachmentError(OSError):
    """첨부 mount를 안전하게 준비하거나 다시 검증하지 못했다."""


def _sandbox_attachment_path_identity(root: pathlib.Path, owner: str) -> tuple:
    """경로를 fd 기준으로 내려가며 모든 중간 링크를 거절하고 디렉터리 식별자를 기록한다.

    사용자 디렉터리는 Control Plane 이 이 요청 전에 만든다. Hermes 는 첨부 루트를 읽기 전용으로 보므로
    여기서는 만들지 않고, 없으면 거절한다(ADR-091).
    """
    directory = root / "users" / _sandbox_attachment_key(owner)
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open("/", flags)
    identities = []
    try:
        for component in directory.parts[1:]:
            child = os.open(component, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            metadata = os.fstat(descriptor)
            identities.append((metadata.st_dev, metadata.st_ino))

        resolved_root = root.resolve(strict=True)
        resolved_directory = directory.resolve(strict=True)
        if resolved_root != root or resolved_directory != resolved_root / "users" / _sandbox_attachment_key(owner):
            raise SandboxAttachmentError("attachment directory resolves outside the execution owner")
        return tuple(identities)
    finally:
        os.close(descriptor)


def _sandbox_attachment_snapshot(policy: dict, owner: str) -> dict:
    """source와 target이 모두 보이고 허용된 사용자 경로 그대로인지 확인한다."""
    try:
        return {
            key: _sandbox_attachment_path_identity(pathlib.Path(policy[key]), owner)
            for key in ("attachment_root", "attachment_agent_root")
        }
    except (OSError, ValueError, RuntimeError) as error:
        raise SandboxAttachmentError("attachment mount paths cannot be verified") from error


def _sandbox_validate_attachment_snapshot(policy: dict, owner: str, expected: dict) -> None:
    if _sandbox_attachment_snapshot(policy, owner) != expected:
        raise SandboxAttachmentError("attachment directories changed after validation")


def _sandbox_verify_attachment_directories(policy: dict, owner: str) -> dict:
    """Control Plane 이 만든 양쪽 사용자 디렉터리를 확인하고 그 식별자를 돌려준다."""
    return _sandbox_attachment_snapshot(policy, owner)


def _sandbox_terminal(policy: dict, profile: str, owner: str, attachment_snapshot: Optional[dict] = None) -> dict:
    """profile 의 `terminal:` 전체다. 모양은 `hermes/README.md` 의 「셸 실행 공간」 과 같다.

    값을 건네는 칸(`docker_forward_env`, `env_passthrough`, `credential_files`)은 비워 둔다.
    profile 의 비밀값이 실행 공간에 들어가지 않게 하려는 것이다.
    """
    if attachment_snapshot is None:
        attachment_snapshot = _sandbox_verify_attachment_directories(policy, owner)
    _sandbox_validate_attachment_snapshot(policy, owner, attachment_snapshot)
    settings = policy["profiles"][profile]
    mounts = policy["read_only_mounts"] + settings["read_only_mounts"]
    # 커넥터가 Hermes 쪽에서 쓴 경로를 스크립트가 그대로 열도록 같은 경로에 읽기 전용으로 붙인다.
    # 실행 공간이 쓰지 못하므로 링크를 바꿔 끼워 커넥터의 쓰기를 다른 곳으로 돌릴 수 없다(ADR-20261008 connector-output-files).
    output = _sandbox_connector_output_profile_directory(policy, profile, owner)
    output_mounts = ["%s:%s:ro" % (output, output)] if output is not None else []
    network = settings["network"]
    extra_args = ["--label=fos-sandbox-profile=%s" % profile]
    if network:
        extra_args.insert(0, "--network=%s" % network)
    terminal = {
        "backend": "docker",
        "cwd": "/workspace",
        "docker_image": policy["image"],
        "container_persistent": True,
        "docker_persist_across_processes": True,
        "docker_orphan_reaper": True,
        "docker_mount_cwd_to_workspace": False,
        "docker_run_as_host_user": False,
        "docker_network": True,
        "docker_extra_args": extra_args,
        "docker_volumes": [
            "%s:/workspace" % _sandbox_workspace(policy, owner),
            "%s:%s:ro" % (_sandbox_attachment_directory(policy, owner),
                            _sandbox_attachment_agent_directory(policy, owner)),
        ] + output_mounts + ["%s:ro" % m for m in mounts],
        "docker_forward_env": [],
        "env_passthrough": [],
        "credential_files": [],
        "container_cpu": policy["cpu"],
        "container_memory": policy["memory_mb"],
    }
    # 빈 `docker_env` 는 칸을 두지 않는다. Hermes 의 config 저장은 빈 dict 를 기본값과 같다고 보고 지우므로
    # (빈 dict 는 `_explicit_config_paths` 의 잎 경로가 아니라 보존되지 않는다), 칸을 넣고 지문을 계산하면
    # 저장된 terminal 로 다시 계산한 지문이 키와 어긋난다. 칸이 없으면 Hermes 는 같은 기본값 `{}` 를 쓴다.
    if settings["env"]:
        terminal["docker_env"] = settings["env"]
    # Hermes 의 docker backend 는 컨테이너를 label 로만 찾아 다시 쓰고, 프로세스 안의 캐시는
    # 지워진 컨테이너를 옛 run 인자로 다시 만든다. 마운트나 이미지가 바뀌어도 새 컨테이너가 생기지 않는다.
    # 이 키가 label 과 캐시 키와 /root 의 디렉터리 이름을 정하므로, 주인이나 실행 공간 설정이 바뀌면
    # 키를 바꿔 컨테이너와 프로세스 캐시와 /root 를 새로 쓰게 한다. 한 키는 이 profile 만 쓴다.
    fingerprint = hashlib.sha256(json.dumps(terminal, sort_keys=True).encode("utf-8")).hexdigest()[:12]
    terminal["docker_shared_container_key"] = "%s-%s-%s" % (profile, owner, fingerprint)
    return terminal
