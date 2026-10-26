# Phase 03. 원격 MCP 플러그인의 확인, 선택지, 승인 실행을 Streamable HTTP 로 부른다

**Execution profile**: deep

## 목표

대시보드 plugin 의 `POST /api/connectors/<id>/call` 과 `POST /api/connectors/<id>/execute` 가 원격 플러그인이면 자식 프로세스 대신 그 주소에 Streamable HTTP 로 붙고, 칸 값을 선언한 헤더로만 보낸다.
연결 등록과 연결 확인, 승인한 쓰기가 원격 플러그인에서도 지금과 같은 응답을 내게 하기 위해서다.

**범위 외**: `prepare`(금융 실행 보호)와 snapshot. 원격 플러그인은 그 선언을 phase 02 에서 이미 거절한다. OAuth 로그인. 금융 워크벤치 전환.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md` 의 「결정」 표(「대시보드의 호출」, 「도구 이름과 승인」 줄), `hermes/plugins/dashboard-profile-api/README.md` 의 「원격 MCP 커넥터는 자식 프로세스를 띄우지 않고 Streamable HTTP 로 붙는다」 목록

- 도구 호출은 `hermes/plugins/dashboard-profile-api/connector_mcp.py` 의 두 함수다
  - `_run_connector_tool(manifest, tool, env)`: `StdioServerParameters` 와 `stdio_client` 로 띄우고 `ClientSession` 으로 `initialize`, `list_tools`, 읽기 전용 확인 뒤 `call_tool(tool, {})`
  - `_run_connector_execute(manifest, hermes_tool, args, env, progress)`: 같은 방식으로 띄우고 등록 이름이 하나로 맞는 도구만 부른다. 부르기 직전에 `progress["sent"] = True`
- `_mcp_sdk_problem()` 이 SDK 버전과 필요한 import 를 확인한다. 지금은 `mcp.client.stdio` 만 본다. SDK 주 버전은 `MCP_SDK_MAJOR = 2` 다
- CI 와 로컬 검사가 고정한 SDK 는 `mcp==2.0.0` 이다(`.github/workflows/ci.yml`, `scripts/dev/check-local.sh` 의 `HERMES_MCP_VERSION`). 이 버전에서
  - 클라이언트는 `mcp.client.streamable_http.streamable_http_client(url, *, http_client: httpx2.AsyncClient | None = None, terminate_on_close: bool = True)` 다. 헤더 인자는 없고, 헤더와 redirect 설정은 미리 만든 `httpx2.AsyncClient` 로 넘긴다
  - HTTP 라이브러리는 `httpx` 가 아니라 `httpx2` 다. SDK 의 기본 생성기 `mcp.shared._httpx_utils.create_mcp_http_client` 는 `follow_redirects=True` 를 켜므로 쓰지 않는다
  - `mcp.server.fastmcp` 는 없다. 서버는 `mcp.server.mcpserver.MCPServer` 이고 `streamable_http_app()` 으로 Streamable HTTP 앱을 만든다. 저장소 선례는 `hermes/tests/fixtures/demo-connector/server.py` 다
  - `httpx2` 는 요청마다 `"httpx2"` logger 에 INFO 로 요청 URL 을 남긴다
- 호출하는 쪽은 `hermes/plugins/dashboard-profile-api/connector_run.py` 다. `call` 은 요청 칸 값으로, `execute` 는 profile `.env` 의 칸 값(`_env_value(env_text, field["env"])`)으로 `env` dict 를 만든다. phase 02 가 둔 `manifest["transport"] == "http"` 의 `unavailable` 갈래가 있다. 시간 제한과 동시 한도(`CONNECTOR_CALL_TIMEOUT_SECONDS`, `CONNECTOR_CALL_LIMIT`), 오류 응답 모양(`{ok: false}` 와 504)은 그대로 쓴다
- 확인 도구와 선택지 도구가 `tools/list` 에서 `readOnlyHint` 가 참이어야 부르는 규칙은 원격에도 그대로다
- 로그를 줄 단위로 모으는 테스트 선례는 `hermes/tests/test_connector_call.py` 의 `collected_logs()` 다(root logger 를 DEBUG 로 모은다). 특정 logger 의 기록을 제외하는 선례는 `connector_diagnostics.py` 의 `_BodylessFilter` 다
- Hermes 의 등록 이름 규칙은 `connector_policy.py` 의 `_hermes_tool_name(server, tool)` 이고 전송과 무관하다. `fos-ctx` 는 서버 이름 접두사로만 판정한다(`hermes/plugins/fos-ctx/README.md` 의 「커넥터 도구 호출을 묻는다」)

## 의도 메모

- 세션 여는 부분만 전송별로 나누고 `initialize` 뒤의 흐름은 한 벌로 둔다. `_open_connector_session(manifest, values)` 비동기 context manager 를 두고 두 함수가 그것을 쓴다
- 원격 요청의 헤더는 manifest 의 `headers` 원문에서 `${이름}` 을 칸 값으로 바꿔 만든다. 칸 값에 CR, LF, NUL 이 있으면 부르지 않는다(`call` 은 이미 `invalid_input` 으로 거절한다. `execute` 도 같은 검사를 한다)
- 클라이언트는 `httpx2.AsyncClient(headers=<만든 헤더>, follow_redirects=False, timeout=<지금 호출 시간 제한>)` 로 만들어 `streamable_http_client(url, http_client=...)` 에 넘긴다. redirect 응답은 따라가지 않으므로 호출 실패가 된다. 토큰 헤더가 다른 호스트로 가는 것을 막는다
- 주소, 헤더 값, 응답 본문을 로그에 싣지 않는다. 대시보드 logger 의 예외는 지금처럼 종류 이름만 남긴다(`_leaf_error_types`). SDK 와 HTTP 라이브러리는 DEBUG 와 INFO 에 요청 URL 과 보낸 메시지를, WARNING 이상에도 예외 문구를 남긴다. 원격 호출 동안 그 logger 들(`httpx2`, `httpcore2.*`, `mcp.client.streamable_http`, `mcp.shared.*`, `ClientSession` 의 logger)의 기록을 수준과 상관없이 제외하는 필터를 단다
- `execute` 에서 요청을 보낸 뒤의 연결 끊김과 시간 초과는 지금처럼 504(실행됐는지 모름)다. 원격은 요청이 서버에 닿았는지 알 수 없는 구간이 자식 프로세스보다 넓다. `progress["sent"]` 를 `call_tool` 직전에 두는 규칙을 그대로 지킨다
- 원격 서버의 Hermes 쪽 동작(헤더의 `${이름}` 풀이, 연결할 때 한 번 정해지는 헤더, redirect)을 `hermes/docs/hermes-contract.md` 에 적는다. 근거는 `hermes/tests/hermes_contract.py` 의 `HERMES_VERSION` tag 의 상류 소스 `tools/mcp_tool_config.py`, `tools/mcp_tool_transport.py` 다. 상류 소스는 공개 저장소에서 읽는다
- Hermes 가 다른 호스트로 가는 redirect 를 따라간다면 Hermes 를 고치지 않고 `hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md` 의 「감당할 것」 에 한 줄 더한다. 따라갈 때 `Authorization` 을 지우는지도 함께 적는다

## Blocked 조건

- 고정한 `mcp` SDK 의 `streamable_http_client` 에 `http_client` 인자가 없다 → `PHASE_BLOCKED: mcp SDK 의 Streamable HTTP 클라이언트가 미리 만든 HTTP 클라이언트를 받지 않는다`

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_mcp.py`

- `_open_connector_session(manifest, values)` 를 더한다. `manifest["transport"]` 가 `stdio` 면 지금의 `stdio_client` 와 `os.devnull` 로 버리는 stderr, `http` 면 위 「의도 메모」 의 `httpx2.AsyncClient` 와 `streamable_http_client` 다. 둘 다 `ClientSession` 을 내준다
- `_run_connector_tool` 과 `_run_connector_execute` 가 이것을 쓴다. 인자 이름 `env` 는 `values` 로 바꾸고, stdio 에서는 지금처럼 env 로, http 에서는 헤더로 쓴다
- `_mcp_sdk_problem` 이 `mcp.client.streamable_http.streamable_http_client` 와 `httpx2` import, `http_client` 인자의 존재도 확인한다
- 파일이 400줄을 넘으면 원격 세션 여는 부분을 phase 02 의 `connector_remote.py` 로 옮긴다

### 2. `hermes/plugins/dashboard-profile-api/connector_run.py`

- phase 02 가 둔 `transport == "http"` 의 `unavailable` 갈래를 지우고 같은 흐름으로 부른다
- `env["PATH"] = os.path.dirname(server["command"])` 는 stdio 일 때만 한다
- `execute` 가 profile `.env` 에서 읽은 칸 값에 CR, LF, NUL 이 있으면 부르지 않고 `{ok: false}` 로 끝낸다

### 3. `hermes/docs/hermes-contract.md`

「MCP 프로세스의 환경 값」 절 뒤에 「원격 MCP 서버」 절을 더한다. `url` 서버의 헤더 `${이름}` 풀이, 연결할 때 한 번 정해지는 헤더, redirect 처리, 상류 소스 링크(위 tag)를 적는다.

### 4. 이 phase 를 검증하는 테스트와 도우미

- `hermes/tests/fixtures/remote_mcp_server.py`(신규): `MCPServer` 로 읽기 전용 확인 도구 하나와 쓰기 도구 하나를 두고, 받은 `Authorization` 헤더를 결과에 싣는다. `streamable_http_app()` 을 빈 포트의 loopback 에서 띄우고 내리는 도우미를 둔다(SDK 가 끌어오는 `uvicorn` 을 쓴다). 다른 주소로 `307` 을 주는 경로와, 요청을 받은 뒤 응답 없이 끊는 도구도 둔다
- `hermes/tests/test_connector_remote_call.py`(신규):
  - `call` 로 확인 도구를 부르면 `{ok: true}` 이고 서버가 받은 헤더가 `Bearer <칸 값>` 이다
  - 읽기 전용이 아닌 도구를 `call` 하면 부르지 않는다
  - `execute` 로 쓰기 도구를 부르면 결과가 오고, 서버가 요청을 받은 뒤 끊으면 504 다
  - 주소가 redirect 를 주면 따라가지 않고 `unavailable` 이며 redirect 대상은 요청을 받지 않는다
  - `test_connector_call.py` 의 `collected_logs()` 같은 방식으로 root logger 를 DEBUG 로 모았을 때, 성공과 실패 모두 모은 로그에 주소와 토큰 문자열이 없다
- `hermes/tests/test_connector_call.py` 와 `hermes/tests/test_connector_execute.py` 의 기존 stdio 테스트가 그대로 통과한다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_remote_call.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_call.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_execute.py'
python3 -m unittest discover -s hermes/tests
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_mcp.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_run.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_remote.py` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md` | 수정 |
| `hermes/tests/fixtures/remote_mcp_server.py` | 신규 |
| `hermes/tests/test_connector_remote_call.py` | 신규 |
| `hermes/tests/hermes_contract.py` | 수정 (plugin 이 새로 import 하는 `httpx2` 를 `THIRD_PARTY_PACKAGES` 에 더한다) |
| `hermes/tests/test_connector_manifest.py` | 수정 (예약 헤더 이름 거절) |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 (phase 02 의 `unavailable` 갈래 단언을 원격 호출 단언으로 바꾼다) |
