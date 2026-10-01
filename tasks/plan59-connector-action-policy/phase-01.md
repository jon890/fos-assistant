# Phase 01. manifest `schema: 2` 와 카탈로그의 도구 정책

**Execution profile**: standard

## 목표

대시보드 plugin 이 `connector.json` 의 `schema: 2` 를 읽어 도구마다 위험도와 승인 방식을 검증하고 카탈로그에 낸다.
Control Plane 이 판정할 정책의 출처가 있어야 하기 때문이다.

**범위 외**: 이름 대응 파일과 설치 변경(phase 02), Control Plane 이 카탈로그를 읽는 쪽(phase 03).

## 컨텍스트

- 고칠 파일은 `hermes/plugins/dashboard-profile-api/__init__.py` 하나다. `_load_connector` 가 manifest 를 검증하고 `_connector_catalog_response` 가 카탈로그를 낸다
- 지금 `_load_connector` 는 `declared["schema"] != 1` 이면 거절한다. 돌려주는 dict 의 `tools` 키는 「대시보드가 `call` 로 부를 수 있는 도구」(`verify.tool` 과 `options.tool`)의 `frozenset` 이고 `_connector_call_request` 가 쓴다
- 테스트는 `hermes/tests/test_connector_manifest.py` 의 `ConnectorGateCase` 를 쓴다. `rewrite(name, change)` 로 fixture 의 JSON 을 고치고 `catalog()` 로 읽는다. `ConnectorCatalogTest` 가 카탈로그 항목의 키 집합을 정확히 단언한다
- fixture 는 `hermes/tests/fixtures/demo-connector/` 다. `server.py` 의 도구는 `list_scopes`, `env_view`(둘 다 읽기 전용), `write_note` 다

**근거 문서**: `docs/connectors.md` 의 「도구 정책」, `docs/adr/ADR-048-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md`, `docs/hermes/connector-policy.md` 의 「MCP 도구의 등록 이름」

## 의도 메모

- 돌려주는 dict 의 기존 `tools` 키 이름은 `call_tools` 로 바꾼다. 새 `tools` 는 도구 정책 dict 다. 같은 이름이 두 뜻을 가지면 뒤 phase 가 틀린 것을 읽는다
- `schema: 1` fixture 는 그대로 둔다. `schema: 1` 을 계속 받는 것을 검사해야 한다. `schema: 2` 는 테스트가 `rewrite` 로 만든다
- 위험도 하한을 어긴 manifest 는 고쳐서 받지 않고 카탈로그에서 뺀다. 조용히 더 엄격하게 읽으면 plugin 을 만든 사람이 선언이 틀린 것을 모른다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py` 의 manifest 검증

상수를 더한다.

```python
TOOL_RISKS = ("READ", "SENSITIVE", "WRITE", "DESTRUCTIVE", "FINANCIAL")
TOOL_APPROVALS = ("none", "required", "always")   # 엄격한 순서
# 위험도마다 (기본 approval, 하한)
TOOL_RISK_DEFAULTS = {"READ": ("none", "none"), "SENSITIVE": ("required", "required"),
                      "WRITE": ("required", "required"), "DESTRUCTIVE": ("always", "always"),
                      "FINANCIAL": ("always", "always")}
TOOL_TITLE_MAX_CHARS = 80
```

Hermes 의 등록 이름을 계산하는 함수를 더한다. 규칙은 `docs/hermes/connector-policy.md` 의 「MCP 도구의 등록 이름」 이다.

```python
def _hermes_tool_name(server: str, tool: str) -> str:
    """Hermes 가 MCP 도구에 붙이는 등록 이름이다. `tools/mcp_tool_schema.py` 의 `mcp_prefixed_tool_name` 과 같은 규칙이다."""
```

- 서버 이름과 도구 이름의 `[^A-Za-z0-9_]` 를 `_` 로 바꾸고 `mcp__<서버>__<도구>` 로 잇는다
- 64자를 넘으면 `full[:55] + "_" + sha256(full.encode("utf-8")).hexdigest()[:8]` 이다

`_connector_tools(declared, verify_tool, option_tools, mcp_server) -> dict` 를 더하고 `_load_connector` 가 부른다. 틀리면 `ValueError` 다.

- `schema` 는 `1` 이나 `2` 만 받는다(`type(...) is int`)
- `schema: 2`
  - `tools` 는 비지 않은 dict 다. 키는 `TOOL_NAME_RE`, 값은 dict 이고 키는 `risk`, `approval`, `title` 밖을 받지 않는다
  - `risk` 는 `TOOL_RISKS` 가운데 하나. `approval` 이 없으면 기본값, 있으면 `TOOL_APPROVALS` 가운데 하나이고 하한보다 느슨하면 거절
  - `title` 은 없거나 1~80자 문자열
  - `default_tool_policy` 는 없거나 `"deny"`
  - `verify_tool` 과 `option_tools` 는 `tools` 에 있고 `risk == "READ"`, `approval == "none"`
  - 도구들의 `_hermes_tool_name(mcp_server, 이름)` 이 서로 겹치면 거절
  - 돌려주는 값은 `{이름: {"risk", "approval"(기본값을 채움), "title"(없으면 None)}}`
- `schema: 1`
  - `tools` 나 `default_tool_policy` 칸이 있으면 거절
  - 돌려주는 값은 `verify_tool` 과 `option_tools` 를 `{"risk": "READ", "approval": "none", "title": None}` 로 담은 dict

`_load_connector` 가 돌려주는 dict 를 고친다.

- `"schema": declared["schema"]` 를 더한다
- 기존 `"tools": frozenset(tools)` 를 `"call_tools": frozenset(tools)` 로 바꾸고 `_connector_call_request` 의 `manifest["tools"]` 를 `manifest["call_tools"]` 로 바꾼다
- `"tools": <위 dict>` 를 더한다

`_connector_catalog_response` 의 항목에 `"schema"` 와 `"tools"` 를 더한다. `tools` 의 `title` 이 None 인 도구는 `title` 키를 내지 않는다.

파일 첫머리의 모듈 docstring 에 커넥터 카탈로그 설명이 있으면 새 칸을 한 줄로 더한다.

### 2. 이 phase 를 검증하는 `hermes/tests/test_connector_manifest.py`

`ConnectorCatalogTest` 의 키 집합 단언을 `{"id", "schema", "title", "description", "fields", "verify", "mcp_server", "toolsets", "attachments", "tools"}` 로 고친다.

테스트를 더한다. `schema: 2` 는 `rewrite("connector.json", ...)` 로 만든다. 기준 선언은 `list_scopes: READ`, `env_view: READ`, `write_note: WRITE` 다.

| 입력 | 기대 |
| --- | --- |
| `schema: 1` fixture 그대로 | 카탈로그 항목의 `schema` 가 1, `tools` 가 `{"list_scopes": {"risk": "READ", "approval": "none"}}` |
| `schema: 2` 기준 선언 | `schema` 가 2, `write_note` 의 `approval` 이 `required` 로 채워짐 |
| `write_note` 에 `title: "메모 쓰기"` | 카탈로그에 `title` 이 그대로 |
| `write_note: {risk: WRITE, approval: none}` | 카탈로그에서 빠진다 |
| `list_scopes: {risk: WRITE}`(확인 도구가 `READ` 가 아님) | 빠진다 |
| `tools` 에 `list_scopes` 가 없음 | 빠진다 |
| `schema: 2` 에 `tools` 없음 | 빠진다 |
| `default_tool_policy: "allow"` | 빠진다 |
| `risk: "DANGEROUS"` | 빠진다 |
| `title` 이 81자 | 빠진다 |
| 등록 이름이 겹치는 두 도구(`a.b` 와 `a-b`) | 빠진다 |
| `purge: {risk: DESTRUCTIVE}` | 받는다. `approval` 이 `always` |
| `purge: {risk: DESTRUCTIVE, approval: required}` | 빠진다 |
| `schema: 3` | 빠진다 |
| `schema: 1` 에 `tools` 칸 | 빠진다 |

`_hermes_tool_name` 의 단위 테스트를 같은 파일에 둔다.

| 입력 | 기대 |
| --- | --- |
| `("policy-probe", "write_item")` | `mcp__policy_probe__write_item` |
| `("demo", "a.b")` | `mcp__demo__a_b` |
| 이은 이름이 64자를 넘는 서버와 도구 | 길이 64, 앞 55자가 원래 이름의 앞 55자, 뒤 9자가 `_` 와 16진수 8자, 같은 입력에 같은 값 |

### 3. `hermes/tests/test_connector_call.py` 확인

`manifest["tools"]` 이름을 바꿨으므로 선택지와 확인 호출 테스트가 그대로 통과하는지 본다. 테스트 파일은 고칠 것이 없어야 한다.

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
scripts/check-public-safe.sh
```

- 셋 다 종료 코드 0
- `grep -n 'manifest\["tools"\]' hermes/plugins/dashboard-profile-api/__init__.py` 의 결과가 모두 도구 정책 dict 를 읽는 자리다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
