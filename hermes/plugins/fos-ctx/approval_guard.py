"""Hermes 승인 관문이 여는 동작을 hook 에서 바로 막는다(스펙 hermes-upgrade REQ5, DECISION8).

Hermes v0.23 은 `/v1/runs` 실행에 승인 알림 경로가 없으면 승인 관문(`tools.approval._run_approval_gate`)을
profile 의 `approvals.unattended_mode` 로 바로 정한다. docker 실행 공간 profile 은 그 값이 `approve` 라
셸 위험 명령, 승인이 필요한 파일 쓰기, `computer_use` 가 사람 없이 열린다. Control Plane 은 Hermes 승인 카드에
답하지 않으므로 v0.21 에서도 이 동작들은 승인 시간을 기다린 뒤 거절된다. 그래서 두 버전 모두 hook 에서 먼저 막는다.

판정은 Hermes 의 함수를 그대로 부른다. 패턴을 복제하면 Hermes 를 올릴 때마다 어긋난다.
기대는 지점은 `hermes/tests/hermes_contract.py` 의 `APPROVAL_GUARD` 와 `APPROVAL_WRITE_POINTS` 가 선언한다.
판정 함수를 불러오지 못하거나 판정이 예외를 던지면 막는다. 단 v0.21 처럼 승인 파일 판정 함수가 없는 Hermes 에서는
파일 판정을 건너뛴다. 그 버전은 승인 카드로 가서 시간 초과로 거절된다.
"""

from __future__ import annotations

import importlib

from .connector_policy import _block
from .context import logger


TERMINAL_TOOL = "terminal"
FILE_WRITE_TOOLS = frozenset({"write_file", "patch"})
COMPUTER_USE_TOOL = "computer_use"

DANGEROUS_COMMAND_MESSAGE = "이 명령은 위험 명령으로 분류돼 실행하지 않는다. 다시 부르지 말고 다른 방법을 찾는다."
APPROVAL_WRITE_MESSAGE = ("이 경로는 쓰려면 사람의 승인이 필요해 쓰지 않는다. "
                          "다시 부르지 말고 셸이나 코드 실행으로 우회하지도 않는다. 다른 방법을 찾는다.")
COMPUTER_USE_MESSAGE = "이 환경에서는 화면 조작 도구를 쓰지 않는다. 다시 부르지 말고 다른 방법을 찾는다."

# Hermes 의 도구 처리기가 `task_id` 가 비면 쓰는 값이다(`tools/file_tools.py` 의 `_handle_write_file`).
DEFAULT_TASK_ID = "default"


def approval_guard(tool_name: str, args, task_id: str):
    """승인 관문으로 갈 호출이면 글이 든 `block` 을, 아니면 None 을 돌려준다. 예외는 던지지 않는다."""
    if tool_name == COMPUTER_USE_TOOL:
        return _block(COMPUTER_USE_MESSAGE)
    if tool_name == TERMINAL_TOOL:
        return _guard(_terminal_blocked, args, task_id, DANGEROUS_COMMAND_MESSAGE)
    if tool_name in FILE_WRITE_TOOLS:
        return _guard(lambda value, task: _write_blocked(tool_name, value, task), args, task_id,
                      APPROVAL_WRITE_MESSAGE)
    return None


def _guard(check, args, task_id, message):
    try:
        blocked = check(args, task_id or DEFAULT_TASK_ID)
    except Exception as exc:  # noqa: BLE001 - 판정하지 못하면 닫힌 쪽으로 막는다
        # 예외 본문에 명령이나 경로가 섞일 수 있어 종류만 남긴다.
        logger.warning("fos-ctx: 승인 관문 대상인지 판정하지 못했다: %s", type(exc).__name__)
        return _block(message)
    return _block(message) if blocked else None


def _terminal_blocked(args, _task_id) -> bool:
    """Hermes 가 위험하다고 분류한 명령인가. `command_allowlist` 는 보지 않는다(스펙 DECISION18)."""
    command = args.get("command") if isinstance(args, dict) else None
    if not isinstance(command, str):
        # 명령이 없는 호출은 Hermes 의 처리기가 거절한다.
        return False
    detection = importlib.import_module("tools.approval_detection")
    return bool(detection.detect_dangerous_command(command)[0])


def _write_blocked(tool_name: str, args, task_id: str) -> bool:
    """대상 경로 가운데 하나라도 Hermes 가 승인을 요구하는 경로인가. 경로를 모으는 방법은 Hermes 의 처리기와 같다."""
    try:
        file_safety = importlib.import_module("agent.file_safety")
    except ImportError:
        return False
    approval_key = getattr(file_safety, "write_approval_key", None)
    if approval_key is None:
        # v0.21 이다. 승인 카드로 가서 시간 초과로 거절된다.
        return False
    if not isinstance(args, dict):
        return False
    paths = _write_paths(tool_name, args)
    if not paths:
        return False
    guards = importlib.import_module("tools.file_tools_write_guards")
    return any(approval_key(guards._resolved_or_raw(path, task_id)) for path in paths)


def _write_paths(tool_name: str, args: dict) -> list:
    """Hermes `write_file_tool`, `patch_tool` 이 승인 판정에 넘기는 경로 목록이다."""
    path = args.get("path")
    paths = [path] if isinstance(path, str) and path else []
    if tool_name == "patch" and args.get("mode", "replace") == "patch" and isinstance(args.get("patch"), str):
        file_tools = importlib.import_module("tools.file_tools")
        collected = file_tools._collect_v4a_header_paths(args["patch"])
        # 글이 오면 경로에 `..` 가 있어 Hermes 의 처리기가 쓰기 전에 거절한다.
        if not isinstance(collected, str):
            paths += collected[0]
    return paths
