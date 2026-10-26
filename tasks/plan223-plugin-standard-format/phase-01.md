# Phase 01. 두 플러그인 manifest 와 `assistant.json` 을 읽는다

**Execution profile**: standard

## 목표

대시보드 plugin 의 manifest 읽기가 `.claude-plugin/plugin.json` 과 `.codex-plugin/plugin.json` 가운데 있는 것을 읽고, assistant 전용 정보를 `assistant.json`(없으면 옛 이름 `connector.json`)에서 읽게 한다.
다른 저장소가 Claude Code 나 Codex 형식으로 만든 플러그인을 고치지 않고 운영 목록에 올리기 위해서다.

**범위 외**: 원격 MCP(`type: http`) 서버. phase 02 와 03 이 맡는다. 이 phase 에서 `.mcp.json` 의 서버 정의 검증은 지금 그대로다. 이 저장소 커넥터의 `connector.json` 이름은 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261026-plugin-standard-format.md` 의 「결정」 표, `hermes/plugins/dashboard-profile-api/README.md` 의 「커넥터」 절(「plugin 디렉터리에서 읽는 것은 넷이다」)

- 읽기는 `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `_load_connector(connector_id, entry)` 한 곳이다
  - 지금 `_read_connector_json(root, ".claude-plugin/plugin.json")` 으로 manifest 를 읽고 `plugin.get("name") != connector_id` 면 거절한다
  - `_read_connector_json(root, "connector.json")` 으로 선언을 읽고, `_read_connector_json(root, ".mcp.json")` 으로 MCP 정의를 읽는다
  - 스킬 경로는 `plugin.get("skills", "./skills")` 다
- `_connector_manifest(connector_id)` 가 `_guard_manifest(_load_connector(...), _read_connector_json(entry["root"], "connector.json"))` 로 선언 원문을 한 번 더 읽는다. 같은 파일을 두 번 고르지 않게 고른 원문을 넘긴다
- `_read_connector_json(root, relative)` 는 `hermes/plugins/dashboard-profile-api/connector_manifest_io.py` 에 있고, 없거나 링크면 `ValueError("<경로> 가 없거나 링크다")` 다
- 테스트 도우미는 `hermes/tests/dashboard_profile_api_support.py` 다. 거기서 플러그인 디렉터리를 만들고(`.claude-plugin/plugin.json`, `connector.json`, `.mcp.json`), `hermes/tests/test_connector_manifest.py` 가 파일마다 틀린 값을 넣는 표 테스트을 둔다
- 이 저장소 커넥터의 공통 검사 `hermes/tests/test_connectors_contract.py` 는 `.claude-plugin/plugin.json` 과 `connector.json` 을 직접 읽는다. 이 phase 는 그 검사를 바꾸지 않는다

## 의도 메모

- 두 manifest 가 다 있을 때 `.claude-plugin` 을 앞세운다. 금융 워크벤치 플러그인처럼 둘을 함께 두는 저장소는 Claude Code 쪽을 먼저 만든다. 두 쪽의 스킬 목록을 합치지 않는다. 합치면 Claude Code 와 Codex 에서 보이는 스킬과 assistant 가 설치하는 스킬이 달라진다
- `assistant.json` 과 `connector.json` 이 둘 다 있으면 거절한다. 어느 쪽이 맞는지 대시보드가 고르면 작성자가 옛 파일을 지우지 않은 채 다른 값을 읽는 일을 모른다
- manifest 의 `mcpServers` 가 객체(인라인 정의)이면 거절한다. 문자열 경로만 받는다. 경로는 플러그인 안의 링크 없는 파일이어야 한다
- `hooks`, `commands`, `agents`, Codex 의 `interface` 는 읽지 않는다. 있다고 거절하지도 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_manifest_io.py`

- `_read_plugin_manifest(root) -> dict` 를 더한다. `.claude-plugin/plugin.json` 과 `.codex-plugin/plugin.json` 을 읽는다
  - 둘 다 없으면 `ValueError("플러그인 manifest 가 없다")`
  - 하나만 있으면 그것을 돌려준다
  - 둘 다 있으면 두 `name` 이 같아야 하고 `.claude-plugin` 쪽을 돌려준다. 다르면 `ValueError("두 플러그인 manifest 의 이름이 다르다")`
  - 파일 하나가 없는 것과 링크인 것을 구분한다. 링크면 지금처럼 `_read_connector_json` 의 예외를 그대로 낸다
- `_read_assistant_json(root) -> dict` 를 더한다. `assistant.json` 이 있으면 그것, 없으면 `connector.json` 이다. 둘 다 있으면 `ValueError("assistant.json 과 connector.json 이 함께 있다")`, 둘 다 없으면 `ValueError("assistant.json 이 없다")`
- `_read_mcp_definition(root, plugin) -> dict` 를 더한다. `plugin.get("mcpServers")` 가 없으면 `.mcp.json`, 문자열이면 그 상대 경로(`./` 를 떼고 읽는다)다. 문자열이 아니거나 플러그인 밖이면 `ValueError("MCP 정의 경로가 올바르지 않다")`

### 2. `hermes/plugins/dashboard-profile-api/connector_manifest.py`

- `_load_connector` 가 위 세 함수를 쓴다. `declared` 의 출처만 바뀌고 검증 규칙은 그대로다. 오류 문구의 `plugin.json` 은 「플러그인 manifest」 로 바꾼다
- `_load_connector` 가 고른 선언 원문을 반환 dict 의 내부 칸으로 넘기지 말고, `_connector_manifest` 가 `_read_assistant_json(entry["root"])` 로 같은 규칙으로 다시 읽어 `_guard_manifest` 에 넘긴다
- docstring 의 「`connector.json`, `.mcp.json`, `plugin.json` 을 읽어」 를 새 이름으로 고친다

### 3. 이 phase 를 검증하는 `hermes/tests/test_connector_manifest.py` 와 `hermes/tests/dashboard_profile_api_support.py`

도우미에 manifest 종류(`claude`, `codex`, `both`)와 선언 파일 이름(`assistant.json`, `connector.json`)을 고르는 인자를 더한다. 기본값은 지금 모양(`claude`, `connector.json`)이라 다른 테스트은 그대로다.

- `.codex-plugin/plugin.json` 만 있고 `skills` 가 목록인 플러그인이 카탈로그에 오르고 그 스킬이 `skills` 에 있다
- 두 manifest 가 있고 이름이 같으면 `.claude-plugin` 의 스킬 경로를 쓴다. 이름이 다르면 카탈로그에서 빠지고 경고에 「이름이 다르다」 가 있다
- `assistant.json` 만 있으면 오르고, `connector.json` 만 있어도 오른다. 둘 다 있으면 빠진다
- manifest 의 `mcpServers` 가 `./mcp/servers.json` 이면 그 파일을 읽는다. 객체면 빠진다. `../x.json` 이면 빠진다
- manifest 가 하나도 없으면 빠진다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'
python3 -m unittest discover -s hermes/tests
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_manifest_io.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
