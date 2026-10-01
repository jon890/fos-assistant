# Phase 01. 대시보드 plugin 이 connector.json 으로 커넥터를 읽고 도구를 부른다

**Execution profile**: deep

## 목표

대시보드 plugin 이 커넥터를 이름으로 박아 두지 않고, 운영 목록의 plugin 디렉터리마다 `connector.json` 을 읽어 카탈로그로 내고, 선택지와 확인 도구를 대신 부르게 한다.
그래야 커넥터를 붙일 때 이 저장소의 코드를 고치지 않는다(ADR-043).

**범위 외**: Control Plane(phase 02), 화면(phase 03), e2e 와 서비스 이름 검사(phase 04). 실제 커넥터의 `connector.json` 은 그 plugin 의 저장소가 따로 더한다.

## 컨텍스트

- 대상: `hermes/plugins/dashboard-profile-api/__init__.py`, 그 검사 `hermes/tests/test_dashboard_profile_api.py`, `hermes/tests/test_connector_roots.py`, 문서 `hermes/README.md`, `docs/code-architecture.md` 의 「Hermes 쪽 코드」
- 지금 plugin 은 커넥터를 하나만 안다. `CONNECTOR_SERVER = "accountbook"`, `ACCOUNTBOOK_ENV_KEYS`, `ENV_KEYS`, `_connector_definition` 의 env 이름 검사와 `ACCOUNTBOOK_FAMILY_UUID` 의 `:-` 예외, `ALLOWED_ROUTES` 의 `/api/mcp/servers/accountbook/test` 가 그 자리다. `grep -n "ACCOUNTBOOK\|accountbook\|CONNECTOR_SERVER" hermes/plugins/dashboard-profile-api/__init__.py` 로 모두 찾는다
- 지금 plugin 디렉터리에서 읽는 것은 `.claude-plugin/plugin.json`, `.mcp.json`, 스킬 본문(persona 로 넣는다)이다. 이 셋은 그대로 읽고 `connector.json` 을 더 읽는다
- 운영 목록은 환경 변수 `FOS_ASSISTANT_CONNECTOR_ROOTS`(`_connector_roots`), 기본 실행 파일은 `FOS_ASSISTANT_CONNECTOR_COMMAND`(`_connector_command`) 다
- MCP 클라이언트는 공식 `mcp` Python SDK 다. Hermes 이미지에 2.0.0 이 있다. `from mcp import ClientSession, StdioServerParameters`, `from mcp.client.stdio import stdio_client`. 정확한 API 는 설치한 판의 소스로 확인한다. 2.0.0 에는 `FastMCP` 가 없고 서버는 `mcp.server.mcpserver.MCPServer` 로 만든다
- **`mcp` 는 `call` 처리 안에서 import 한다.** SDK 가 없는 환경에서도 대시보드 plugin 이 올라오고 나머지 경로가 돌아야 한다. import 가 실패하면 `call` 만 `unavailable` 이다

**근거 문서**: `docs/connectors.md` 의 「connector.json」 과 「대시보드 plugin 계약」, `docs/code-architecture.md` 의 「Hermes 쪽 코드」 환경 변수 표, `docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md`

## 의도 메모

- **한 배포 동안 옛 Control Plane 의 호출도 받는다**(ADR-041). 옛 Control Plane 은 `POST /api/mcp/servers/accountbook/test`, `PUT /api/connectors` 의 `plugin: fos-accountbook`, 가계부 env key 세 개를 쓴다. 새 범용 경로가 이 모양을 그대로 받으면 된다. 가계부 `connector.json` 이 그 env 이름을 선언하기 때문이다. 옛 Control Plane 은 운영자 env(공통 주소)도 `PUT /api/env` 로 쓴다. 그 이름은 새 manifest 의 `operator_env` 이므로 **성공으로 답하되 아무것도 쓰지 않는다**(`restart_required: false`)
- **이미 설치된 profile 의 소유 기록(`.fos-connectors.json`)을 깨지 않는다.** 서버 정의를 비교하는 규칙이 바뀌어 기존 기록이 409 나 503 이 되면 운영의 가계부 연결이 끊긴다. 검사 fixture 에 지금 판이 쓰는 기록 모양을 넣고 새 코드가 그대로 인정하는지 본다. 옛 기록은 운영자 env 를 `${이름}` 참조로 갖고 새 정의는 값을 직접 갖는다. **소유 기록을 비교할 때 `operator_env` 키는 옛 `${이름}` 참조와 새 직접 값을 같다고 본다.** 다음 설치(`enabled: true`) 때 기록을 새 모양으로 다시 쓴다
- `FOS_ASSISTANT_CONNECTOR_ROOTS` 의 값은 문자열(옛 모양)과 object(새 모양) 둘 다 받는다. 문자열이면 `{"root": 그 값}` 으로 읽는다
- `operator_env` 의 값은 운영 목록에서만 온다. 사용자 요청의 `PUT /api/env` 로 쓰지 못하고, 설치할 때 서버 정의의 env 에 그 값을 직접 넣는다. 지금 가계부의 공통 주소처럼 profile `.env` 에 이미 있는 값은 그대로 둬도 동작은 같다
- `call` 은 후보 값을 디스크에 쓰지 않는다. 자식 프로세스의 env 로만 넘기고, 응답과 로그에 값을 담지 않는다
- 대시보드는 비동기 처리기 안에서 돈다. `call` 은 `asyncio.wait_for(…, 10)` 으로 묶고 동시 실행을 프로세스 전체 4개로 센다. 시간을 넘기면 자식 프로세스를 끝내고 `unavailable` 이다. **이미 4개가 돌고 있으면 기다리지 않고 바로 `unavailable` 이다**(줄을 세우지 않는다). `call` 은 profile 쓰기 잠금 밖에서 돈다. 잠금 안에서 돌면 10초 동안 모든 profile 요청이 멈춘다

## Blocked 조건

- `python3 -c "import mcp"` 가 실패하고 `pip install 'mcp==2.0.0'` 도 안 된다 → `PHASE_BLOCKED: mcp SDK 를 설치하지 못했다`

## 작업 항목

### 1. manifest 읽기와 검증

`_connector_manifest(connector_id) -> dict | None` 를 더한다.
- 운영 목록에 없거나, `connector.json` 이 없거나, 아래 검증 하나라도 실패하면 `None` 과 경고 로그 한 줄. 예외를 밖으로 던지지 않는다
- 검증: `docs/connectors.md` 「connector.json」 표의 모든 형식 규칙. `schema == 1`, `id` 가 목록 이름과 같음, `fields[].key` 유일, `fields[].env` 가 `.mcp.json` 서버 env 의 `${이름}` 참조와 맞음(선택 칸은 `${이름:-}` 도 받는다. 지금 가계부 plugin 의 가족 칸이 그 모양이다), `fields[].env ∪ operator_env == 서버 env 키`, `options.tool`, `verify.tool` 이 문자열, `errors` 값이 공통 어휘 넷 가운데 하나, `operator_env` 의 값이 운영 목록 항목 `env` 에 모두 있음
- `.mcp.json` 은 서버 하나만 받는다. 그 이름이 manifest 의 MCP 서버 이름(`mcp_server`)이다
- 실행 정의도 범용으로 바꾼다. 지금은 `dist/accountbook-mcp.js` 와 bun 이 박혀 있다. 새 규칙: `.mcp.json` 서버의 `args` 가운데 `${CLAUDE_PLUGIN_ROOT}` 로 시작하는 것은 plugin root 안의 링크 없는 파일이어야 하고 실제 경로로 바꾼다. `command` 는 운영 목록 항목의 `command`(없으면 `FOS_ASSISTANT_CONNECTOR_COMMAND`)로 바꾼다. 실행 권한이 없으면 그 커넥터는 쓸 수 없다
- 스킬 경로(`skills`) 검사와 persona 에 지침을 넣는 지금 동작은 그대로 둔다
- 도구의 `readOnlyHint` 는 `call` 할 때 `tools/list` 로 확인한다. manifest 읽기에서는 이름 형식만 본다(`docs/connectors.md` 「connector.json」 과 같다)

### 2. 범용 커넥터 경로

`CONNECTOR_SERVER`, `ACCOUNTBOOK_ENV_KEYS` 상수를 없애고 manifest 에서 계산한다.
- `GET /api/connectors/catalog` 새 경로: `[{id, title, description, fields, verify, mcp_server}]`. `fields` 는 manifest 의 칸 그대로(`env`, `options` 포함), `verify` 는 `{tool}`. Control Plane 이 `env` 로 `PUT /api/env` 의 key 를 정하고 `verify.tool` 로 확인 도구를 부르기 때문이다. `operator_env` 의 이름과 값, `errors` 는 담지 않는다
- `GET /api/connectors?profile=`, `PUT /api/connectors`: plugin 이름을 운영 목록과 manifest 로 확인한다. 응답 모양은 그대로
- **운영 목록에서 빠진 커넥터**: 그 profile 에 소유 기록이 남아 있으면 `PUT /api/connectors` 의 `enabled: false` 를 받아 설치를 끄고, 그 기록의 서버 env 가 `${이름}` 으로 참조하던 key 를 profile `.env` 에서 지운다. `enabled: true` 는 지금처럼 거절한다. `GET /api/connectors` 는 그 기록 때문에 예외를 내지 않고 `configured: false` 로 낸다
- `PUT /api/env`, `DELETE /api/env`: 기본 key 셋은 그대로 두고, 커넥터 key 는 카탈로그 manifest 의 `fields[].env` 합으로 계산한다. 관리 표식이 있는 profile 에만. `operator_env` 이름은 성공으로 답하고 쓰지 않는다(`restart_required: false`)
- `POST /api/mcp/servers/{server}/test?profile=`: 서버 이름이 그 profile 에 설치된 커넥터의 `mcp_server` 일 때만 넘긴다. `ALLOWED_ROUTES` 는 경로 패턴으로 바꾼다
- 선택 칸(`required: false`)이 비어 있을 때 서버 env 에 빈 값을 명시하는 지금 가계부 예외를 모든 선택 칸으로 넓힌다

### 3. `POST /api/connectors/{id}/call`

본문 `{tool, values}`. 정확히 그 두 키만 받는다.
- `tool` 이 그 manifest 의 `options.tool` 이나 `verify.tool` 이 아니면 400
- `values` 의 키는 그 manifest 의 `fields[].key` 만, 값은 문자열만. `pattern` 이 있으면 검사. 위반은 `{ok: false, error: "invalid_input"}` 200
- 자식 env 는 `fields` 의 `env` 에 `values` 를 넣고 운영 목록의 `env` 를 더한다. 그 밖의 env 는 넘기지 않는다(PATH 는 실행 파일 디렉터리만)
- 자식을 띄워 `initialize`, `tools/list` 로 그 도구의 `annotations.readOnlyHint` 가 참인지 확인, `tools/call` 한 번, 닫기
- 결과: `structuredContent` 가 있으면 그것, 없으면 첫 텍스트 칸을 JSON 으로. `isError` 면 `error.code` 를 manifest `errors` 로 바꾼다. 표에 없거나 읽지 못하면 `unavailable`
- 응답 `{ok: true, result}` 또는 `{ok: false, error}`. HTTP 는 둘 다 200, 경로나 tool 이 틀린 것만 4xx
- 시간 제한 10초, 동시 4개. 4개가 차 있으면 기다리지 않고 `unavailable`
- `tool` 이 manifest 에 있지만 `tools/list` 의 `readOnlyHint` 가 참이 아니면 400 이다

### 4. `hermes/README.md` 갱신

「dashboard-profile-api 가 여는 것」 표와 커넥터 절을 범용 모양으로 고친다. 계약 자체는 `docs/connectors.md` 를 링크하고 되풀이하지 않는다. `FOS_ASSISTANT_CONNECTOR_ROOTS` 의 새 object 모양을 적는다.
`docs/code-architecture.md` 「Hermes 쪽 코드」 의 「필요한 것은 Python 과 PyYAML 뿐」 문장에 `mcp` SDK 를 더한다.

### 5. 검사

`hermes/tests/fixtures/demo-connector/` (신규): 시험용 커넥터 plugin
- `.claude-plugin/plugin.json`, `.mcp.json`(서버 `demo`, env `DEMO_TOKEN`, `DEMO_SCOPE`, `DEMO_BASE`), `connector.json`(id `demo-notes`, 칸 `token` 비밀 필수 pattern, `scope` 선택 options, verify, operator_env `DEMO_BASE`, errors), 스킬 하나
- `server.py`: `mcp` SDK 의 `MCPServer` 로 만든 stdio 서버. 도구 `list_scopes`(readOnlyHint 참. 토큰이 `demo_ok_0123456789` 면 `{"scopes": [{"id": "a", "name": "A"}]}`, `demo_bad_0123456789` 면 `isError` 와 `{"error": {"code": "DEMO_UNAUTHORIZED"}}`, `demo_odd_0123456789` 면 `isError` 와 manifest `errors` 에 없는 코드, `demo_slow_0123456789` 면 시간 제한보다 오래 기다린다), `write_note`(readOnlyHint 거짓)
- 토큰 `pattern` 은 `^demo_[a-z]+_[0-9]{10}$`. 시험 토큰을 8자보다 길게 두는 까닭은 Control Plane 이 앞 8자만 저장하기 때문이다
- 실행 파일은 `sys.executable`, 인자는 `server.py`

`hermes/tests/test_connector_manifest.py` (신규)
- 정상: 시험 커넥터가 카탈로그에 나오고 `fields[].env` 와 `verify.tool` 이 있고, 응답 어디에도 `operator_env` 의 값이 없다
- 실패: `connector.json` 이 없음, `schema` 2, env 가 `.mcp.json` 과 다름, `errors` 값이 어휘 밖, `operator_env` 값이 목록에 없음 → 각각 카탈로그에서 빠지고 예외 없음
- `FOS_ASSISTANT_CONNECTOR_ROOTS` 의 문자열 모양과 object 모양을 둘 다 읽는다

`hermes/tests/test_connector_call.py` (신규)
- 정상: `list_scopes` 를 `demo_ok_0123456789` 로 부르면 `{ok: true, result: {"scopes": [...]}}`
- 실패: `demo_bad_0123456789` 는 `credential_rejected`, 모르는 오류 코드(`demo_odd_0123456789`)는 `unavailable`, manifest 에 없는 tool 은 400, **`verify.tool` 이 `write_note` 인 manifest 변형**으로 `write_note` 를 부르면 400(읽기 전용 확인을 지우면 이 검사가 실패해야 한다), 동시 실행이 이미 4개면 다섯 번째는 기다리지 않고 `unavailable`, 모르는 values 키나 pattern 위반은 `invalid_input`, 10초를 넘기는 도구(시험에서는 제한을 짧게 바꿔 끼운다)는 `unavailable` 이고 자식이 남지 않는다
- 응답 본문과 로그에 `demo_ok_0123456789` 원문이 없다

`hermes/tests/test_dashboard_profile_api.py`
- 가계부 전용 단언을 시험 커넥터 기준으로 바꾼다
- **옛 호출 호환**: 운영 판이 남긴 소유 기록 모양 그대로의 fixture 로 `GET /api/connectors` 가 `configured: true`, `POST /api/mcp/servers/<그 서버>/test` 가 통과한다. 운영자 env 이름의 `PUT /api/env` 가 성공으로 답하고 profile `.env` 를 바꾸지 않는다
- **목록에서 빠진 커넥터**: 소유 기록만 남은 profile 에서 `PUT /api/connectors` `enabled: false` 가 설치를 끄고 그 기록이 참조하던 env key 를 지운다. `GET /api/connectors` 가 예외 없이 답한다

## 검증

```bash
# cwd: 저장소 root
python3 -m pip install 'mcp==2.0.0' 'PyYAML==6.0.3'
python3 -m unittest discover -s hermes/tests -v
! git grep -niE "accountbook|ACCOUNTBOOK_" -- hermes/plugins
scripts/quality.sh check
scripts/check-public-safe.sh
```

- 첫 검사는 새 검사 셋을 포함해 모두 통과해야 하고, 실행 뒤 남은 `server.py` 자식 프로세스가 없어야 한다(`pgrep -f demo-connector/server.py` 가 비어 있음)
- `.github/workflows/ci.yml` 의 `hermes` job 이 `mcp==2.0.0` 을 설치하게 고친다. `scripts/check-local.sh` 의 hermes 단계는 `python3 -c "import mcp, yaml"` 이 실패할 때만 `python3 -m pip install 'mcp==2.0.0' 'PyYAML==6.0.3'` 을 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/README.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/tests/test_connector_roots.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 신규 |
| `hermes/tests/test_connector_call.py` | 신규 |
| `hermes/tests/fixtures/demo-connector/.claude-plugin/plugin.json` | 신규 |
| `hermes/tests/fixtures/demo-connector/.mcp.json` | 신규 |
| `hermes/tests/fixtures/demo-connector/connector.json` | 신규 |
| `hermes/tests/fixtures/demo-connector/server.py` | 신규 |
| `hermes/tests/fixtures/demo-connector/skills/demo/SKILL.md` | 신규 |
| `.github/workflows/ci.yml` | 수정 |
| `scripts/check-local.sh` | 수정 |
