# Phase 01. ChatGPT 이식형 묶음의 manifest 와 MCP 정의를 읽는다

**Execution profile**: standard

## 목표

OpenAI 문서의 이식형 플러그인(root `plugin.json`, root `mcp.json`, `skills/`)을 Claude Code·Codex 형식과 같은 카탈로그 항목으로 읽는다.
`assistant.json` 이 없으면 `schema: 3` 으로 오르고, 있으면 지금처럼 그 선언으로 읽는다.

**범위 외**: OAuth(`auth.type` 이 `oauth`, `mixed`), 위젯 UI, `.app.json`, `interface`, `onboardingSkill`, `hooks`.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261101-plugin-without-assistant-json.md` 의 「ChatGPT 형식에서는 MCP 서버 연결과 스킬만 받는다」 표와 「형식 비교」

- manifest 는 `hermes/plugins/dashboard-profile-api/connector_manifest_io.py` 의 `_read_plugin_manifest` 가, MCP 정의는 `_read_mcp_definition` 이 읽는다
- 원격 서버 정의의 검증은 `connector_remote.py` 의 `_remote_server` 가 한다. `type` 은 지금 `stdio` 와 `http` 만 받는다
- 칸 추론은 `connector_standard.py` 의 `_inferred_fields` 다

## 의도 메모

- manifest 가 셋(`.claude-plugin`, `.codex-plugin`, root `plugin.json`) 가운데 여럿이면 `name` 이 모두 같아야 한다. 경로는 `.claude-plugin`, root `plugin.json`, `.codex-plugin` 순으로 앞선 것을 쓴다
- root `mcp.json` 은 `.mcp.json` 이 없을 때만 읽는다. 둘 다 있으면 카탈로그에서 뺀다. 하나를 골라 읽으면 작성자가 어느 쪽이 쓰이는지 모른다
- `type: streamable-http` 는 `http` 와 같게 읽는다. 주소는 지금처럼 운영 목록의 `url` 만 쓴다
- `extensions["com.openai"].auth` 는 `none` 과 `api_key` 만 받는다. `api_key` 의 `headerScheme: bearer` 는 `Authorization: Bearer ${<이름>}`, `custom_header` 는 `headerName: ${<이름>}` 헤더 하나다. 칸 env 이름은 `<서버 이름 대문자>_API_KEY` 로 만들고 원격 칸 이름 규칙을 지킨다. `basic` 과 그 밖은 카탈로그에서 뺀다

## 작업 항목

### 1. manifest 와 MCP 정의 읽기

위 순서로 manifest 를 고르고, `mcp.json` 의 서버 하나를 읽는다. `extensions` 아래에서는 위 `auth` 만 읽는다.

### 2. 칸 추론

`auth.api_key` 의 헤더를 서버 정의의 `headers` 로 바꾼 뒤 지금의 원격 검증에 넘긴다.

### 3. 이 phase 를 검증하는 테스트

`hermes/tests/fixtures/` 에 이식형 fixture 를 두고 `hermes/tests/test_connector_standard_plugin.py` 에 더한다.

- 이식형 묶음이 `schema: 3` 으로 오르고 칸이 `api_key` 헤더에서 온다
- `oauth`, `mixed`, `basic` 이나 `mcp.json` 과 `.mcp.json` 이 함께 있으면 빠진다
- 위젯 `_meta` 가 있는 서버도 오르되 그 자원을 설치하거나 부르지 않는다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_standard_plugin.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_manifest_io.py`, `connector_remote.py`, `connector_standard.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/README.md` 의 「커넥터」 | 수정 |
| `hermes/tests/fixtures/` 의 이식형 fixture | 신규 |
