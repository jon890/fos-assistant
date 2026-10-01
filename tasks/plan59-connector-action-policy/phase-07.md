# Phase 07. 대시보드 plugin 의 실행 경로

**Execution profile**: standard

## 목표

대시보드 plugin 에 `POST /api/connectors/{id}/execute` 를 더한다. Control Plane 이 승인한 호출을 저장한 인자로 한 번 실행할 자리다.

**범위 외**: Control Plane 이 이 경로를 부르는 것(phase 08).

## 컨텍스트

- 고칠 파일은 `hermes/plugins/dashboard-profile-api/__init__.py` 다
- 본보기는 `_connector_call_request(request, connector_id)` 와 `_run_connector_tool(manifest, tool, env)` 다. `call` 은 후보 값을 env 로 넘겨 자식을 띄우고 `tools/list` 에서 `read_only_hint is True` 인 도구만 부른다. 동시 실행 수는 `_connector_calls` 와 `CONNECTOR_CALL_LIMIT`, 결과 변환은 `_connector_call_answer(manifest, result)` 다
- 경로를 여는 자리는 `CALL_ROUTE_RE` 가 쓰이는 곳들이다. `ProfileApiProvider` 와 `_install_gate` 에서 `CALL_ROUTE_RE` 를 찾아 같은 방식으로 더한다
- profile 디렉터리는 `hermes_cli.profiles.get_profile_dir(name)`, 관리 표식은 `MANAGED_MARKER`, 소유 기록은 `CONNECTOR_STATE` 와 `_connector_state(...)`, `.env` 값 읽기는 `_env_value(env_text, key)` 다. profile 이름 검사는 `_profile_rejection`, `_missing_profile` 이다
- phase 01 이 `_hermes_tool_name(server, tool)` 과 manifest 의 `schema`, `tools` 를 만들었다
- 테스트는 `hermes/tests/test_connector_call.py` 의 `ConnectorCallTest(base.ConnectorGateCase)` 방식이다. 실제 자식 프로세스를 띄우고 `pgrep -f server.py` 로 남은 자식이 없는지 본다. fixture `hermes/tests/fixtures/demo-connector/server.py` 의 `write_note` 는 `read_only_hint=False` 다

**근거 문서**: `docs/connectors.md` 의 「승인」 절 끝의 `execute` 계약, `docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md`

## 의도 메모

- `call` 의 「읽기 전용 도구만」 규칙은 건드리지 않는다. `execute` 는 다른 경로다
- 대시보드는 승인 여부를 다시 확인하지 않는다. 서비스 토큰을 가진 Control Plane 을 믿는다
- 등록 이름으로 도구를 찾는다. `schema: 1` 은 원래 이름을 Control Plane 이 모르는 호출이 있기 때문이다. `tools/list` 의 도구마다 `_hermes_tool_name` 을 계산해 정확히 하나가 맞을 때만 부른다
- 인자와 결과를 로그에 싣지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py`

- `EXECUTE_ROUTE_RE = re.compile(r"^/api/connectors/([^/]+)/execute$")`, `CONNECTOR_EXECUTE_TIMEOUT_SECONDS = 60`
- `_run_connector_execute(manifest, hermes_tool, args, env)`: 자식을 띄워 `initialize`, `list_tools` 뒤 `_hermes_tool_name(manifest["mcp_server"], item.name) == hermes_tool` 인 도구를 모은다. 하나가 아니면 None. `schema == 2` 이고 그 이름이 `manifest["tools"]` 에 없으면 None. 있으면 `session.call_tool(이름, args)` 의 결과를 돌려준다
- `_connector_execute_request(request, connector_id)`:
  - 본문은 정확히 `{"profile", "hermes_tool", "args"}`. `hermes_tool` 은 1~128자 문자열, `args` 는 dict. 아니면 400
  - 모르는 커넥터는 404. profile 검사는 `_profile_rejection`, `_missing_profile`. 관리 표식이 없으면 401
  - 그 profile 의 소유 기록에 이 커넥터가 없으면 404 `설치하지 않은 connector 다`
  - env 는 그 profile `.env` 에서 `manifest["fields"]` 의 `env` 이름만 `_env_value` 로 꺼낸다. 값이 빈 선택 칸은 빈 문자열로 준다. 운영 목록의 값(`server["env"]` 가운데 `manifest["operator_env"]`)과 `PATH`(실행 파일의 디렉터리)를 `call` 과 같은 방식으로 더한다
  - `_connector_calls` 한도를 `call` 과 함께 쓴다. 가득 찼으면 `{"ok": false, "error": "unavailable"}`
  - `asyncio.wait_for(..., CONNECTOR_EXECUTE_TIMEOUT_SECONDS)`. 시간 초과는 504 와 `{"detail": ...}` 로 답한다. Control Plane 이 「결과를 모른다」 로 읽어야 하므로 `{"ok": false}` 로 답하지 않는다
  - 다른 예외는 종류만 로그에 남기고 `{"ok": false, "error": "unavailable"}`. 자식을 띄우기 전에 난 것이므로 실행되지 않은 것으로 본다. 단 `call_tool` 을 보낸 뒤의 예외는 실행 여부를 모르므로 504 로 답한다. 둘을 나누려면 `_run_connector_execute` 가 `call_tool` 을 부르기 직전에 표시를 남긴다
  - 결과가 None 이면 400 `실행할 수 없는 도구다`
  - 그 밖은 `_connector_call_answer(manifest, result)` 를 200 으로 답한다
- 경로를 `CALL_ROUTE_RE` 와 같은 자리에 연다. 메서드는 `POST` 다
- 모듈 docstring 의 경로 설명에 한 줄을 더한다

### 2. fixture

`hermes/tests/fixtures/demo-connector/server.py` 의 `write_note` 가 받은 인자와 env 의 `DEMO_TOKEN` 앞 4자를 결과로 돌려주게 한다(지금 무엇을 돌려주는지 읽고, 인자가 그대로 닿았는지 볼 수 있게만 고친다). 느린 실행을 흉내 낼 방법이 `call` 테스트에 이미 있으면(`SLOW_TOKEN`) 같은 방법을 쓴다.

### 3. 이 phase 를 검증하는 `hermes/tests/test_connector_execute.py`(신규)

`import test_connector_manifest as base` 로 `ConnectorExecuteTest(base.ConnectorGateCase)` 를 만든다. profile 디렉터리와 `hermes_cli.profiles.get_profile_dir` 대역이 `ConnectorGateCase` 에 없으면 `test_dashboard_profile_api.py` 의 `ProfileApiRouteTest` 를 기반으로 삼는다. 어느 쪽이 맞는지는 두 기반을 읽고 정한다.

| 상황 | 기대 |
| --- | --- |
| 설치한 profile, `mcp__demo__write_note`, `{"text": "안녕"}` | 200 `{"ok": true, "result": ...}`. 결과에 `안녕` 이 있다. 남은 자식 프로세스가 없다 |
| 같은 요청의 env | 자식이 그 profile `.env` 의 `DEMO_TOKEN` 을 받았다. 대시보드 프로세스의 다른 env 는 받지 않았다 |
| `mcp__demo__nope` | 400 |
| `schema: 2` 인데 `tools` 에 없는 도구의 등록 이름 | 400. 자식이 그 도구를 부르지 않았다 |
| 설치하지 않은 profile | 404 |
| 관리 표식 없는 profile | 401 |
| 모르는 커넥터 | 404 |
| `args` 가 목록 | 400 |
| 본문에 다른 키 | 400 |
| 시간 초과(테스트는 제한을 줄인다) | 504. 남은 자식이 없다 |
| 토큰 없이 | 401 |
| 도구가 `isError` 로 답 | 200 `{"ok": false, "error": <공통 어휘>}` |

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- `call` 은 자식을 띄우기 전에 `_mcp_sdk_problem()` 으로 `mcp` SDK 가 지원 범위인지 본다. `execute` 도 같은 자리에서 보고, 범위 밖이면 `{"ok": false, "error": "unavailable"}` 로 답한다(실행되지 않았다)
- `hermes/README.md` 의 대시보드 경로 표에 `POST /api/connectors/<id>/execute` 한 줄을, 커넥터 절에 「Control Plane 이 승인한 호출만 부른다. 대시보드는 승인 여부를 다시 확인하지 않는다」 를 더한다

- **서버 이름 길이를 제한한다.** `_load_connector` 가 `_hermes_tool_name(mcp_server, "")` 의 길이가 40자를 넘는 커넥터를 카탈로그에서 뺀다. 접두사가 길면 등록 이름이 64자에서 잘려 Control Plane 의 접두사 검사(`ConnectorPolicyService.ownServerTool`)가 그 서버의 긴 도구를 선언 없는 도구로 읽는다. `hermes/tests/test_connector_manifest.py` 에 경계 테스트를 더하고 「변경 파일」 에 그 파일을 수정으로 더한다. `docs/connectors.md` 의 「도구 정책」 목록에 한 줄을 더한다

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
python3 -m unittest discover -s hermes/tests -p 'test_connector_execute.py'
scripts/check-public-safe.sh
```

- 모두 종료 코드 0

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/fixtures/demo-connector/server.py` | 수정 |
| `hermes/tests/test_connector_execute.py` | 신규 |
| `hermes/README.md` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `docs/connectors.md` | 수정 |
