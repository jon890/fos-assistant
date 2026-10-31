"""커넥터 운영 목록과 manifest 원문을 읽는다."""

from __future__ import annotations

import os
import pathlib

from .common import logger
from .connector_guard import _strict_json
from .connector_schema import CONNECTOR_COMMAND_ENV, CONNECTOR_ROOTS_ENV


def _connector_roots() -> dict[str, dict]:
    """운영 목록을 읽는다. `{"<커넥터 이름>": {"root", "command", "env", "url"}}` 다. 없거나 틀리면 빈 dict 다.

    환경 변수의 값은 이름마다 문자열(plugin 디렉터리)이나
    `{"root": "<plugin 디렉터리>", "command": "<실행 파일>", "env": {"<이름>": "<값>"}, "url": "<원격 MCP 주소>"}` 다.
    문자열은 `{"root": 그 값}` 으로 읽는다. `url` 은 없으면 None 이고, 모양은 원격 MCP 서버 정의를 읽을 때 본다.
    """
    raw = os.environ.get(CONNECTOR_ROOTS_ENV, "").strip()
    if not raw:
        return {}
    try:
        value = _strict_json(raw)
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
        root, command, env, url = entry.get("root"), entry.get("command"), entry.get("env", {}), entry.get("url")
        if (set(entry) - {"root", "command", "env", "url"} or not isinstance(root, str)
                or not (command is None or isinstance(command, str)) or not isinstance(env, dict)
                or not (url is None or isinstance(url, str))
                or any(not isinstance(key, str) or not isinstance(item, str) for key, item in env.items())):
            logger.warning("dashboard-profile-api: %s 의 %s 항목 모양이 올바르지 않아 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        if not os.path.isabs(root) or (command is not None and not os.path.isabs(command)):
            logger.warning("dashboard-profile-api: %s 의 %s 경로가 절대 경로가 아니라 버렸다", CONNECTOR_ROOTS_ENV, name)
            continue
        roots[name] = {"root": pathlib.Path(root), "command": command, "env": dict(env), "url": url}
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
    return _strict_json(path.read_text(encoding="utf-8"))


def _read_plugin_manifest(root: pathlib.Path) -> dict:
    """`.claude-plugin/plugin.json` 과 `.codex-plugin/plugin.json` 가운데 있는 것을 읽는다.

    둘 다 있으면 이름이 같아야 하고 `.claude-plugin` 쪽을 쓴다. 두 쪽의 스킬 목록은 합치지 않는다.
    합치면 Claude Code 나 Codex 에서 보이는 스킬과 assistant 가 설치하는 스킬이 달라진다.
    """
    # 없는 파일과 링크를 가른다. 링크는 `_read_connector_json` 이 거절한다.
    present = [relative for relative in (".claude-plugin/plugin.json", ".codex-plugin/plugin.json")
               if os.path.lexists(root / relative)]
    if not present:
        raise ValueError("플러그인 manifest 가 없다")
    manifests = [_read_connector_json(root, relative) for relative in present]
    if any(not isinstance(manifest, dict) for manifest in manifests):
        raise ValueError("플러그인 manifest 는 객체여야 한다")
    if len(manifests) == 2 and manifests[0].get("name") != manifests[1].get("name"):
        raise ValueError("두 플러그인 manifest 의 이름이 다르다")
    return manifests[0]


def _read_assistant_json(root: pathlib.Path):
    """assistant 전용 선언을 읽는다. `assistant.json` 이 있으면 그것, 없으면 옛 이름 `connector.json` 이다.

    둘 다 있으면 거절한다. 하나를 골라 읽으면 작성자가 옛 파일을 지우지 않은 채 다른 값을 읽는 일을 모른다.
    둘 다 없으면 None 이다. 표준 플러그인으로 읽는다(ADR-20261101 plugin-without-assistant-json).
    """
    present = [relative for relative in ("assistant.json", "connector.json") if os.path.lexists(root / relative)]
    if len(present) == 2:
        raise ValueError("assistant.json 과 connector.json 이 함께 있다")
    if not present:
        return None
    return _read_connector_json(root, present[0])


def _read_mcp_definition(root: pathlib.Path, plugin: dict):
    """플러그인 manifest 의 `mcpServers` 경로에서 MCP 정의를 읽는다. 칸이 없으면 `.mcp.json` 이다.

    인라인 정의(객체)는 받지 않는다. 경로는 플러그인 안의 링크 없는 파일이어야 한다.
    """
    relative = plugin.get("mcpServers", ".mcp.json")
    if not isinstance(relative, str):
        raise ValueError("MCP 정의 경로가 올바르지 않다")
    relative = relative.removeprefix("./")
    path = pathlib.Path(os.path.normpath(root / relative))
    if not relative or path == root or not path.is_relative_to(root):
        raise ValueError("MCP 정의 경로가 올바르지 않다")
    return _read_connector_json(root, relative)
