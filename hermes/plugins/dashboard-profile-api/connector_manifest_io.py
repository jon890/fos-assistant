"""커넥터 운영 목록과 manifest 원문을 읽는다."""

from __future__ import annotations

import json
import os
import pathlib

from .common import logger
from .connector_schema import CONNECTOR_COMMAND_ENV, CONNECTOR_ROOTS_ENV


def _connector_roots() -> dict[str, dict]:
    """운영 목록을 읽는다. `{"<커넥터 이름>": {"root", "command", "env"}}` 다. 없거나 틀리면 빈 dict 다.

    환경 변수의 값은 이름마다 문자열(plugin 디렉터리)이나
    `{"root": "<plugin 디렉터리>", "command": "<실행 파일>", "env": {"<이름>": "<값>"}}` 다.
    문자열은 `{"root": 그 값}` 으로 읽는다.
    """
    raw = os.environ.get(CONNECTOR_ROOTS_ENV, "").strip()
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except ValueError:
        value = None
    if not isinstance(value, dict) or any(not isinstance(entry, (str, dict)) for entry in value.values()):
        # 값에는 운영 경로가 들어 있어 로그에 싣지 않는다.
        logger.warning("dashboard-profile-api: %s 가 문자열이나 object 값의 JSON object 가 아니다", CONNECTOR_ROOTS_ENV)
        return {}
    roots = {}
    for name, entry in value.items():
        if isinstance(entry, str):
            entry = {"root": entry}
        root, command, env = entry.get("root"), entry.get("command"), entry.get("env", {})
        if (set(entry) - {"root", "command", "env"} or not isinstance(root, str)
                or not (command is None or isinstance(command, str)) or not isinstance(env, dict)
                or any(not isinstance(key, str) or not isinstance(item, str) for key, item in env.items())):
            logger.warning("dashboard-profile-api: %s 의 %s 항목 모양이 올바르지 않아 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        if not os.path.isabs(root) or (command is not None and not os.path.isabs(command)):
            logger.warning("dashboard-profile-api: %s 의 %s 경로가 절대 경로가 아니라 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        roots[name] = {"root": pathlib.Path(root), "command": command, "env": dict(env)}
    return roots


def _connector_command() -> str | None:
    """커넥터 MCP 서버를 실행할 파일의 기본 절대 경로다. 없거나 절대 경로가 아니면 None 이다."""
    command = os.environ.get(CONNECTOR_COMMAND_ENV, "").strip()
    if not command:
        return None
    if not os.path.isabs(command):
        logger.warning("dashboard-profile-api: %s 가 절대 경로가 아니다", CONNECTOR_COMMAND_ENV)
        return None
    return command


def _read_connector_json(root: pathlib.Path, relative: str):
    path = root / relative
    if path.resolve() != path or not path.is_file():
        raise ValueError("%s 가 없거나 링크다" % relative)
    return json.loads(path.read_text(encoding="utf-8"))


