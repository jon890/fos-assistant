"""커넥터 manifest 와 소유 기록이 함께 쓰는 형식 규칙이다."""

from __future__ import annotations

import re


# 요청은 이름만 받는다. 실행 정의는 커넥터 checkout 의 manifest 가 소유한다.
# 커넥터 이름과 plugin 디렉터리를 묶은 JSON 을 대시보드 프로세스의 환경 변수로 받는다. 근거는 ADR-041 이 갖는다.
CONNECTOR_ROOTS_ENV = "FOS_ASSISTANT_CONNECTOR_ROOTS"


# 커넥터 MCP 서버를 실행할 파일의 절대 경로다. 운영 목록 항목에 `command` 가 없을 때 쓴다.
CONNECTOR_COMMAND_ENV = "FOS_ASSISTANT_CONNECTOR_COMMAND"


CONNECTOR_STATE = ".fos-connectors.json"


# 소유 기록 항목의 설치 방식이다. 칸이 없으면 옛 설치다.
BIND_MODE = "bind"


ISOLATED_MODE = "isolated"


CONNECTOR_SKILL_PARTS = ("references", "templates")


# 커넥터 스킬 하나의 상한이다. Control Plane 이 올린 스킬에 거는 제한과 같다(`docs/backend/skill.md`).
CONNECTOR_SKILL_MAX_FILES = 20


CONNECTOR_SKILL_MAX_CHARS = 100_000


# `connector.json` 의 형식 규칙이다. `docs/connectors.md` 의 「connector.json」 표와 같다.
CONNECTOR_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")


FIELD_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")


ENV_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,127}$")


# 바인딩 주인의 첨부 디렉터리를 받을 env 이름이다(ADR-20261007 connector-owner-attachments).
OWNER_ATTACHMENTS_ENV_RE = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")


# 그 env 에 설치가 넣는 값의 끝 모양이다. `<attachment_agent_root>/users/<SHA-256 16진수>` 다.
OWNER_ATTACHMENTS_VALUE_RE = re.compile(r"^/[^$\0\r\n]*/users/[0-9a-f]{64}$")


# 커넥터 출력 디렉터리를 받을 env 에 설치가 넣는 값의 끝 모양이다.
# `<connector_output_root>/users/<SHA-256 16진수>/<profile>/<커넥터 id>` 다(ADR-20261008 connector-output-files).
OWNER_OUTPUT_VALUE_RE = re.compile(r"^/[^$\0\r\n]*/users/[0-9a-f]{64}/[^/$\0\r\n]+/[^/$\0\r\n]+$")


TOOL_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")


SERVER_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")


ERROR_WORDS = frozenset({"credential_rejected", "forbidden", "invalid_input", "unavailable", "outcome_unknown"})


# 쓰기를 보냈는데 됐는지 모른다는 어휘다. 실행 경로만 504 로 답하고 `call` 은 `unavailable` 로 읽는다.
OUTCOME_UNKNOWN = "outcome_unknown"


# `errors` 표의 오류 코드 형식이다. 승인한 호출의 실행 경로가 이 코드를 Control Plane 에 그대로 넘긴다(ADR-092).
ERROR_CODE_RE = re.compile(r"[A-Z][A-Z0-9_]{0,63}")


# `errors` 표의 객체 항목이 고를 수 있는 복구 어휘다. 커넥터는 글을 쓰지 않고 어휘만 고른다.
# Control Plane 의 `ConnectorRecovery` 와 같다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
ERROR_RECOVERIES = frozenset({"recheck", "reconnect", "fix_input", "retry_later"})


# 오류 하나가 넘길 수 있는 세부 칸의 수와 정수 값의 절댓값 상한이다. Control Plane 이 같은 상한으로 다시 본다.
ERROR_DETAILS_MAX = 4


# `connector.json` 의 `toolsets` 가 열 수 있는 내장 toolset 이다. 읽기 전용 이미지 도구만 둔다(ADR-044).
# 셸, 파일, 기억, 스킬, 위임 도구는 manifest 로 열리지 않는다.
CONNECTOR_TOOLSETS = frozenset({"vision"})


# `connector.json` 의 `schema: 2` 가 도구마다 선언하는 위험도와 승인 방식이다(ADR-049).
# 표는 `docs/backend/connector-tool-policy.md` 의 「도구 정책」 과 같다.
TOOL_RISKS = ("READ", "SENSITIVE", "WRITE", "DESTRUCTIVE", "FINANCIAL")


# 느슨한 것에서 엄격한 것의 순서다. 하한 비교가 이 순서의 자리를 쓴다.
TOOL_APPROVALS = ("none", "required", "always")


# 위험도마다 (`approval` 이 없을 때의 기본값, 선언이 내려갈 수 없는 하한) 이다.
TOOL_RISK_DEFAULTS = {"READ": ("none", "none"), "SENSITIVE": ("required", "required"),
                      "WRITE": ("required", "required"), "DESTRUCTIVE": ("always", "always"),
                      "FINANCIAL": ("always", "always")}


TOOL_TITLE_MAX_CHARS = 80


# `tools.<이름>.identifiers` 가 가리키는 인자 이름이다(ADR-089). 도구 인자 객체의 맨 위 칸만 가리킨다.
# 31자까지다. 32자 이상인 이름은 Control Plane 이 키 이름 자체를 긴 덩어리로 보고 가린다.
TOOL_IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,30}")


# 비밀 키로 읽히는 인자 이름이다. Control Plane 의 `ToolDetailRedactor` 의 `SECRET_KEYS` 와 `isSecretKey` 와 같다.
# 한쪽을 바꾸면 다른 쪽도 바꾼다. 어긋나도 Control Plane 이 그 칸을 다시 가리므로 비밀이 보이지는 않는다.
SECRET_ARGUMENT_NAMES = frozenset({"token", "secret", "password", "passwd", "apikey", "authorization", "cookie",
                                   "credential", "credentials", "privatekey", "accesskey", "clientsecret"})


SECRET_ARGUMENT_SUFFIXES = ("token", "secret", "password", "privatekey")


# Hermes 가 MCP 도구의 등록 이름에 허용하는 길이다. 넘으면 앞부분에 해시를 붙여 줄인다.
HERMES_TOOL_NAME_MAX_CHARS = 64


# 서버 이름으로 계산한 등록 이름의 앞부분(`mcp__<서버>__`)이 넘지 못하는 길이다.
# 앞부분이 길면 등록 이름이 잘릴 때 앞부분까지 잘려, Control Plane 이 그 서버의 도구임을 알아보지 못한다.
HERMES_TOOL_PREFIX_MAX_CHARS = 40


# 설치가 연결용 profile 의 `SOUL.md` 에 쓰는 스킬 본문의 상한이다. Control Plane 의 성격 본문 상한과 같다.
CONNECTOR_PERSONA_MAX_CHARS = 8000


# `.mcp.json` 의 인자가 plugin 디렉터리를 가리키는 자리다. 그 밖의 치환은 받지 않는다.
PLUGIN_ROOT_REF = "${CLAUDE_PLUGIN_ROOT}"
