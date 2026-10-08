"""운영 목록의 커넥터를 읽고 검증해 카탈로그로 낸다."""

from __future__ import annotations

import json
import os
import pathlib

# 옛 기능 모듈의 import 계약을 유지하려고 이동한 이름도 다시 내보낸다.

from .common import (
    BASE_ENV_KEYS,
    CONTROL_PLANE_MCP,
    logger,
)

from .connector_appearance import (
    _connector_icon,
    _connector_link,
)

from .connector_policy import (
    _canonical_server_name,
    _connector_errors,
    _connector_fields,
    _connector_tools,
    _hermes_tool_name,
    _tool_identifiers,
)

from .connector_schema import (
    BIND_MODE,
    CONNECTOR_COMMAND_ENV,
    CONNECTOR_ID_RE,
    CONNECTOR_PERSONA_MAX_CHARS,
    CONNECTOR_ROOTS_ENV,
    CONNECTOR_SKILL_MAX_CHARS,
    CONNECTOR_SKILL_MAX_FILES,
    CONNECTOR_SKILL_PARTS,
    CONNECTOR_STATE,
    CONNECTOR_TOOLSETS,
    ENV_NAME_RE,
    ERROR_CODE_RE,
    ERROR_DETAILS_MAX,
    ERROR_RECOVERIES,
    ERROR_WORDS,
    FIELD_KEY_RE,
    HERMES_TOOL_NAME_MAX_CHARS,
    HERMES_TOOL_PREFIX_MAX_CHARS,
    ISOLATED_MODE,
    OUTCOME_UNKNOWN,
    OWNER_ATTACHMENTS_ENV_RE,
    OWNER_ATTACHMENTS_VALUE_RE,
    OWNER_BROWSER_LOGIN_URL_MAX_CHARS,
    OWNER_BROWSER_VALUE_RE,
    OWNER_OUTPUT_VALUE_RE,
    PLUGIN_ROOT_REF,
    SECRET_ARGUMENT_NAMES,
    SECRET_ARGUMENT_SUFFIXES,
    SERVER_NAME_RE,
    TOOL_APPROVALS,
    TOOL_IDENTIFIER_RE,
    TOOL_NAME_RE,
    TOOL_RISKS,
    TOOL_RISK_DEFAULTS,
    TOOL_TITLE_MAX_CHARS,
)

from .connector_owner_env import (
    _connector_owner_env,
)

from .connector_skills import (
    _connector_persona,
    _connector_skills,
    _skill_name,
)


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


def _appearance_or_none(connector_id: str, name: str, read):
    """카드 칸 하나를 읽는다. 틀리면 그 칸만 None 으로 두고 경고를 남긴다(ADR-20261008 connector-card).

    카탈로그는 도구 정책 판정과 바인딩 설치도 먹이므로, 장식 칸 하나 때문에 커넥터를 빼지 않는다.
    로그에는 경로와 값을 싣지 않고 직접 낸 사유만 적는다.
    """
    try:
        return read()
    except Exception as error:
        reason = str(error) if type(error) is ValueError else type(error).__name__
        logger.warning("dashboard-profile-api: 커넥터 %r 의 %s 칸을 버렸다: %s", connector_id, name, reason)
        return None


def _load_connector(connector_id: str, entry: dict) -> dict:
    """plugin 디렉터리의 `connector.json`, `.mcp.json`, `plugin.json` 을 읽어 검증한다. 틀리면 예외다.

    형식 규칙은 `docs/connectors.md` 의 「connector.json」 이 소유한다(ADR-043).
    """
    root = entry["root"]
    if not CONNECTOR_ID_RE.match(connector_id):
        raise ValueError("커넥터 이름이 올바르지 않다")
    if root.resolve() != root:
        raise ValueError("connector 경로에 심볼릭 링크가 있다")
    plugin = _read_connector_json(root, ".claude-plugin/plugin.json")
    if not isinstance(plugin, dict) or plugin.get("name") != connector_id:
        raise ValueError("plugin.json 의 이름이 운영 목록과 다르다")

    declared = _read_connector_json(root, "connector.json")
    if not isinstance(declared, dict) or type(declared.get("schema")) is not int or declared["schema"] not in (1, 2):
        raise ValueError("schema 는 1 이나 2 만 받는다")
    if declared.get("id") != connector_id:
        raise ValueError("id 가 운영 목록의 이름과 다르다")
    if (not isinstance(declared.get("title"), str) or not declared["title"]
            or not isinstance(declared.get("description", ""), str)):
        raise ValueError("title 과 description 은 문자열이다")
    icon = _appearance_or_none(connector_id, "icon", lambda: _connector_icon(root, declared.get("icon")))
    link = _appearance_or_none(connector_id, "link", lambda: _connector_link(declared.get("link")))
    fields = _connector_fields(declared.get("fields"))
    verify = declared.get("verify")
    if (not isinstance(verify, dict) or not isinstance(verify.get("tool"), str)
            or not TOOL_NAME_RE.match(verify["tool"])):
        raise ValueError("verify.tool 은 도구 이름이다")
    field_env = {field["env"] for field in fields}
    operator_env = declared.get("operator_env", [])
    if (not isinstance(operator_env, list)
            or any(not isinstance(name, str) or not ENV_NAME_RE.match(name) or name in BASE_ENV_KEYS
                   for name in operator_env)
            or len(set(operator_env)) != len(operator_env) or set(operator_env) & field_env):
        raise ValueError("operator_env 는 칸과 겹치지 않는 env 이름 목록이다")
    if set(operator_env) - set(entry["env"]):
        raise ValueError("operator_env 의 값이 운영 목록에 없다")
    (owner_attachments_env, owner_output_env, owner_browser_env, owner_browser_login_url,
     owner_env) = _connector_owner_env(declared, field_env, operator_env)
    operator_secrets = declared.get("operator_secrets", [])
    if not isinstance(operator_secrets, list) or any(not isinstance(name, str) for name in operator_secrets):
        raise ValueError("operator_secrets 는 env 이름 목록이다")
    if operator_secrets:
        # 조용히 무시하면 비밀이 필요한 커넥터의 확인이 까닭 없이 실패한다. 받지 못하는 칸임을 밝힌다(ADR-046).
        raise ValueError("operator_secrets 는 아직 지원하지 않는다")
    errors, error_contracts = _connector_errors(declared.get("errors", {}))
    toolsets = declared.get("toolsets", [])
    if (not isinstance(toolsets, list) or any(not isinstance(name, str) for name in toolsets)
            or len(set(toolsets)) != len(toolsets) or set(toolsets) - CONNECTOR_TOOLSETS):
        raise ValueError("toolsets 는 허용한 내장 toolset 이름의 겹치지 않는 목록이다")
    attachments = declared.get("attachments", False)
    if not isinstance(attachments, bool) or (attachments and "vision" not in toolsets):
        raise ValueError("attachments 는 boolean 이고 참이면 toolsets 에 vision 이 있어야 한다")

    mcp = _read_connector_json(root, ".mcp.json")
    if not isinstance(mcp, dict):
        raise ValueError("MCP manifest 는 객체여야 한다")
    if "mcpServers" in mcp:
        if set(mcp) != {"mcpServers"}:
            raise ValueError("MCP manifest 에 다른 필드가 있다")
        mcp = mcp["mcpServers"]
    if not isinstance(mcp, dict) or len(mcp) != 1:
        raise ValueError("MCP 서버 하나만 허용한다")
    mcp_server, server = next(iter(mcp.items()))
    # 맞춘 이름으로 견준다. `fos_assistant` 나 `FOS-Assistant` 처럼 글자만 다른 이름도 Control Plane MCP 의 이름으로 읽힐 수 있어
    # 그 커넥터의 도구가 Control Plane 도구와 같은 이름 공간에 놓인다.
    if (not SERVER_NAME_RE.match(mcp_server)
            or _canonical_server_name(mcp_server) == _canonical_server_name(CONTROL_PLANE_MCP)):
        raise ValueError("MCP 서버 이름이 올바르지 않다")
    if len(_hermes_tool_name(mcp_server, "")) > HERMES_TOOL_PREFIX_MAX_CHARS:
        raise ValueError("MCP 서버 이름이 길어 등록 이름의 앞부분이 %d자를 넘는다" % HERMES_TOOL_PREFIX_MAX_CHARS)
    if (not isinstance(server, dict) or set(server) - {"command", "args", "env"}
            or not isinstance(server.get("args"), list) or not isinstance(server.get("env"), dict)):
        raise ValueError("MCP 서버 정의 모양이 올바르지 않다")
    optional_env = frozenset(field["env"] for field in fields if field.get("required", True) is False)
    if set(server["env"]) != field_env | set(operator_env) | owner_env:
        raise ValueError("MCP 서버 env 가 fields 와 operator_env, owner_attachments_env, owner_output_env, "
                         "owner_browser_env 의 합과 다르다")
    for name, value in server["env"].items():
        # 비밀값 원문이나 다른 변수의 참조를 받지 않는다. 선택 칸만 빈 기본값 참조를 쓸 수 있다.
        if value != "${%s}" % name and not (name in optional_env and value == "${%s:-}" % name):
            raise ValueError("MCP 서버 env 는 자기 이름의 참조만 허용한다")
    args = []
    for arg in server["args"]:
        if not isinstance(arg, str):
            raise ValueError("MCP 서버 인자는 문자열이다")
        if arg.startswith(PLUGIN_ROOT_REF + "/"):
            target = root / arg[len(PLUGIN_ROOT_REF) + 1:]
            if target.resolve() != target or not target.is_relative_to(root) or not target.is_file():
                raise ValueError("MCP 서버 인자의 파일이 plugin 안의 링크 없는 파일이 아니다")
            arg = str(target)
        elif "$" in arg:
            raise ValueError("MCP 서버 인자에 plugin root 밖의 치환이 있다")
        args.append(arg)
    # 실행 파일은 manifest 가 정하지 못한다. 운영 목록의 값이나 기본 실행 파일만 쓴다.
    command = entry["command"] or _connector_command()
    if command is None or not os.access(command, os.X_OK):
        raise ValueError("커넥터 실행 파일이 없거나 실행할 수 없다")

    # 옛 설치는 스킬 본문을 `SOUL.md` 에 써 지침으로 넣고 skills toolset 은 열지 않는다.
    # 바인딩 설치는 스킬 디렉터리를 그 profile 의 스킬로 복사한다. 둘 다 같은 디렉터리를 읽는다.
    skills = plugin.get("skills", "./skills")
    if isinstance(skills, str):
        skills = [skills]
    if not isinstance(skills, list) or any(not isinstance(item, str) for item in skills):
        raise ValueError("스킬 경로 목록이 올바르지 않다")
    skill_dirs = []
    for item in skills:
        path = root / item
        if not path.resolve().is_relative_to(root) or not path.is_dir() or path.resolve() != path.absolute():
            raise ValueError("스킬은 plugin 안의 링크 없는 디렉터리여야 한다")
        skill_dirs.append(path.resolve())
    persona = _connector_persona(skill_dirs)
    installed_skills = _connector_skills(skill_dirs)

    env = {name: "${%s}" % name for name in server["env"]}
    # 운영자 env 는 profile `.env` 를 거치지 않는다. 운영 목록의 값을 서버 정의에 직접 넣는다.
    env.update({name: entry["env"][name] for name in operator_env})
    # 주인의 첨부 디렉터리와 커넥터 출력 디렉터리도 profile `.env` 를 거치지 않는다. 바인딩 설치가 이 빈 값을 그 디렉터리로 바꾼다.
    # 주인을 모르는 설치는 빈 값 그대로 두어 커넥터가 사용자 첨부를 읽지 않고 파일을 쓰지 않는다.
    # 브라우저 중계 주소도 같다. 바인딩 설치가 이 빈 값을 그 바인딩의 중계 주소로 바꾼다.
    env.update({name: "" for name in owner_env})
    option_tools = {field["options"]["tool"] for field in fields if "options" in field}
    tools = _connector_tools(declared, verify["tool"], option_tools, mcp_server)
    definition = {"command": command, "args": args, "env": env, "enabled": True}
    # 늘 승인이 필요한 도구는 모델에 등록하지 않는다. 도구 이름에는 glob 글자가 없어 그 이름만 빠진다.
    excluded = sorted(name for name, policy in tools.items() if policy["approval"] == "always")
    if excluded:
        definition["tools"] = {"exclude": excluded}
    return {
        "id": connector_id,
        "schema": declared["schema"],
        "title": declared["title"],
        "description": declared.get("description", ""),
        "icon": icon,
        "link": link,
        "fields": fields,
        "verify": {"tool": verify["tool"]},
        "mcp_server": mcp_server,
        "operator_env": frozenset(operator_env),
        "owner_attachments_env": owner_attachments_env,
        "owner_output_env": owner_output_env,
        "owner_browser_env": owner_browser_env,
        "owner_browser_login_url": owner_browser_login_url,
        "optional_env": optional_env,
        "errors": errors,
        "error_contracts": error_contracts,
        "toolsets": list(toolsets),
        "attachments": attachments,
        "persona": persona,
        "skills": installed_skills,
        # 대시보드가 `call` 로 부를 수 있는 도구다. 도구 정책인 `tools` 와 뜻이 다르다.
        "call_tools": frozenset({verify["tool"]} | option_tools),
        "tools": tools,
        "server": definition,
    }


def _connector_manifest(connector_id: str) -> dict | None:
    """운영 목록에 있고 검증을 통과한 커넥터의 manifest 다. 아니면 경고 한 줄을 남기고 None 이다."""
    entry = _connector_roots().get(connector_id)
    if entry is None:
        logger.warning("dashboard-profile-api: 커넥터 %r 가 운영 목록에 없다", connector_id)
        return None
    try:
        return _load_connector(connector_id, entry)
    except Exception as error:
        # 파일 내용과 운영 경로를 로그에 싣지 않는다. 직접 낸 사유만 그대로 적는다.
        reason = str(error) if type(error) is ValueError else type(error).__name__
        logger.warning("dashboard-profile-api: 커넥터 %r 를 쓸 수 없다: %s", connector_id, reason)
        return None


def _connector_catalog() -> dict[str, dict]:
    """운영 목록의 커넥터 가운데 검증을 통과한 manifest 다."""
    manifests = {name: _connector_manifest(name) for name in _connector_roots()}
    return {name: manifest for name, manifest in manifests.items() if manifest is not None}


def _connector_env_keys() -> tuple[frozenset, frozenset]:
    """카탈로그 manifest 의 칸 env 이름과 운영자 env 이름이다. 칸 이름과 겹치는 운영자 이름은 칸으로 본다."""
    fields, operator = set(), set()
    for manifest in _connector_catalog().values():
        fields.update(field["env"] for field in manifest["fields"])
        operator.update(manifest["operator_env"])
    return frozenset(fields), frozenset(operator - fields)


def _entry_mode(entry) -> str:
    """소유 기록 항목의 설치 방식이다. 칸이 없으면 옛 설치다."""
    return entry.get("mode", ISOLATED_MODE) if isinstance(entry, dict) else ISOLATED_MODE


def _server_matches(manifest: dict, server: dict) -> bool:
    """소유 기록의 서버 정의가 지금 manifest 의 실행 정의와 같은지 본다.

    칸의 env 는 자기 이름의 참조이고, 선택 칸은 명시한 빈 값도 된다.
    운영자 env 는 옛 기록의 `${이름}` 참조와 지금 정의의 직접 값을 같다고 본다.
    옛 판이 남긴 기록을 그대로 인정해야 이미 설치한 연결이 끊기지 않는다(ADR-041).
    `tools` 는 견주지 않는다. 옛 기록에는 그 키가 없고, 다시 보낸 설치가 지금 manifest 의 값으로 덮어쓴다.
    주인의 첨부 디렉터리 env 는 설치마다 그 주인의 값이라 manifest 와 견주지 않는다. 빈 값이거나
    `<루트>/users/<64자리 16진수>` 모양인지만 본다. 참조(`${...}`)를 받으면 profile `.env` 가 경로를 정하게 된다(ADR-20261007 connector-owner-attachments).
    커넥터 출력 디렉터리 env 도 같다. `<루트>/users/<64자리 16진수>/<profile>/<id>` 모양인지만 본다(ADR-20261008 connector-output-files).
    브라우저 중계 주소 env 도 같다. 빈 값이거나 `<gateway-base-url>/<접근 표식>` 모양인지만 본다(ADR-20261008 browser-gateway-token).
    """
    expected = manifest["server"]
    if (server["command"] != expected["command"] or server["args"] != expected["args"]
            or set(server["env"]) != set(expected["env"])):
        return False
    for name, value in server["env"].items():
        reference = "${%s}" % name
        if name == manifest["owner_attachments_env"]:
            if value != "" and not OWNER_ATTACHMENTS_VALUE_RE.match(value):
                return False
        elif name == manifest["owner_output_env"]:
            if value != "" and not OWNER_OUTPUT_VALUE_RE.fullmatch(value):
                return False
        elif name == manifest["owner_browser_env"]:
            if value != "" and not (isinstance(value, str) and OWNER_BROWSER_VALUE_RE.fullmatch(value)):
                return False
        elif name in manifest["operator_env"]:
            if value not in (reference, expected["env"][name]):
                return False
        elif value != reference and not (value == "" and name in manifest["optional_env"]):
            return False
    return True


def _connector_catalog_response():
    """검증을 통과한 커넥터의 카탈로그다. 운영자 env 의 이름과 값, 오류 대응 표, 스킬 본문은 담지 않는다.

    `skills` 는 바인딩 설치가 profile 에 복사할 스킬의 이름이다. 이름 순이다.
    `owner_browser` 는 사용자 브라우저를 쓰는 커넥터인지이고, `owner_browser_login_url` 은 로그인 안내 주소나 null 이다.
    env 이름은 내지 않는다.
    """
    from starlette.responses import JSONResponse

    return JSONResponse(
        [{"id": manifest["id"], "schema": manifest["schema"], "title": manifest["title"],
          "description": manifest["description"], "icon": manifest["icon"], "link": manifest["link"],
          "fields": manifest["fields"], "verify": manifest["verify"], "mcp_server": manifest["mcp_server"],
          "toolsets": manifest["toolsets"], "attachments": manifest["attachments"],
          "owner_browser": manifest["owner_browser_env"] is not None,
          "owner_browser_login_url": manifest["owner_browser_login_url"],
          "skills": sorted(manifest["skills"]),
          # 사람 말 제목이 없는 도구는 `title` 을 내지 않는다. 읽는 쪽이 도구 이름을 보인다.
          "tools": {name: {key: value for key, value in policy.items() if value is not None}
                    for name, policy in manifest["tools"].items()}}
         for manifest in _connector_catalog().values()],
        status_code=200,
    )
