# Phase 05. `fos-ctx` hook 이 커넥터 도구 호출을 Control Plane 에 묻는다

**Execution profile**: deep

## 목표

연결용 profile 의 `fos-ctx` hook 이 커넥터 MCP 도구 호출마다 Control Plane 에 묻고, 답이 없거나 틀리면 막는다.
홈서버 없이 전체 흐름을 보는 e2e 시나리오를 더한다.

**범위 외**: 승인 요청 번호를 담은 `block` 의 처리(phase 08. hook 은 Control Plane 이 준 글을 그대로 쓰므로 hook 은 바뀌지 않는다).

## 컨텍스트

- 고칠 plugin 은 `hermes/plugins/fos-ctx/__init__.py` 다. `pre_tool_call(tool_name="", args=None, session_id="", tool_call_id="", **_)` 가 `skill_manage` 를 막고, `TOOL_PREFIX = "mcp__fos_assistant__"` 인 도구에 `_fos_ctx` 를 붙이고, 그 밖은 `None` 을 돌려준다
- 재료: `_read_token()`(profile 의 MCP 토큰), `signing_key(token)`, `root_session(session_id)`, `_post(url, body, token)`(상태 코드만 돌려줌), `_state_db_path()` 가 쓰는 `hermes_constants.get_hermes_home()`
- 대응 파일은 그 profile 디렉터리(`get_hermes_home()`)의 `.fos-connector-tools.json` 이다. phase 02 가 설치 때 쓴다
- Control Plane 경로는 phase 04 가 만든 `POST /internal/hermes/connector-policy` 다. 서명 벡터는 `backend/src/test/java/com/bifos/assistant/mcp/application/ConnectorPolicyRequestTest.java` 의 `VECTOR_*` 상수다
- Python 테스트는 `hermes/tests/test_fos_ctx.py` 다. `PluginFixture` 가 가짜 `agent.secret_scope` 와 `hermes_constants` 를 끼우고 임시 디렉터리를 home 으로 준다. `SubagentRegistrationTest` 가 `http.server` 로 가짜 서버를 띄우는 방식을 쓴다
- e2e 대역 `test/e2e/fake-hermes.ts` 는 run 안의 MCP 호출을 흉내 낼 때 Control Plane 을 직접 부른다(`writeArtifactViaMcp`, `registerSubagent`). 입력 글자가 분기를 고른다(`specialOutputFor`). profile 의 env 는 `profileEnv(name)` 로 읽는다. 서명 도우미는 `test/e2e/mcp-context.ts` 다
- e2e 시나리오는 `test/e2e/scenarios/` 에 하나씩 두고 `test/e2e/run.ts` 의 `SCENARIOS` 에 넣는다

**근거 문서**: `docs/connectors.md` 의 「도구 호출 판정」, `docs/hermes/connector-policy.md` 의 「막히는 것과 통과하는 것」, 「제한 시간」, `docs/adr/ADR-048-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md`

## 의도 메모

- 막을 때는 늘 비지 않은 글이 든 `block` 이다. Hermes 는 글이 없는 `block` 과 `None` 을 통과로 읽는다
- 커넥터 갈래 전체를 `try` 로 감싼다. 어떤 예외든 정해 둔 글로 막는다. 예외를 밖으로 던지면 본문 일부가 모델에게 간다
- HTTP 는 한 번만 부르고 다시 부르지 않는다. 제한은 3초다. 기다리는 동안 run 의 스레드가 묶인다
- 대응 파일이 없는 profile 은 지금과 똑같이 동작해야 한다. 일반 에이전트의 MCP 도구를 건드리지 않는다
- 인자는 `json.dumps(args, sort_keys=True, separators=(",", ":"), ensure_ascii=False)` 로 직렬화한 글을 보내고 그 글을 서명한다

## 작업 항목

### 1. `hermes/plugins/fos-ctx/__init__.py`

상수를 더한다.

```python
POLICY_URL_ENV = "FOS_CTX_POLICY_URL"
POLICY_VERSION = "v1-connector-policy"
POLICY_TIMEOUT = 3.0
CONNECTOR_TOOL_MAP = ".fos-connector-tools.json"
MCP_PREFIX = "mcp__"
CODE_EXECUTION_TOOL = "execute_code"
POLICY_BLOCK_MESSAGE = "fos-ctx: 이 도구 호출의 사용 정책을 확인하지 못해 막았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다."
CONTEXT_BLOCK_MESSAGE = "fos-ctx: 이 도구 호출의 실행 맥락이 없어 막았다."
UNKNOWN_SERVER_MESSAGE = "fos-ctx: 이 연결에 등록되지 않은 도구라 막았다. 다시 부르지 않는다."
CODE_EXECUTION_MESSAGE = "fos-ctx: 이 연결에서는 코드 실행으로 도구를 부를 수 없다."
```

함수를 더한다.

- `sign_policy(key, hermes_tool, root_session_id, session_id, tool_call_id, args_json) -> str`: 서명할 글은 `POLICY_VERSION`, `hermes_tool`, `root_session_id`, `session_id`, `tool_call_id`, `hashlib.sha256(args_json.encode("utf-8")).hexdigest()` 를 `\n` 으로 이은 것
- `read_tool_map(home=None)`: 대응 파일이 없으면 `None`. 있으면 `{"v": 1, "servers": {...}}` 모양을 검사해 `servers` dict 를 돌려준다. 읽지 못하거나 모양이 틀리면 `ValueError`. `home` 이 없으면 `hermes_constants.get_hermes_home()`
- `connector_policy(tool_name, args, session_id, tool_call_id, servers)`: 아래 순서로 `None`(통과)이나 `block` dict 를 돌려준다
  1. `servers` 에서 `tool_name.startswith(prefix)` 인 서버를 찾는다. 없으면 `UNKNOWN_SERVER_MESSAGE` 로 막는다
  2. `session_id` 나 `tool_call_id` 가 비었거나 `args` 가 dict 가 아니면 `CONTEXT_BLOCK_MESSAGE` 로 막는다. `args` 가 None 이면 `{}` 로 읽는다
  3. `tool = server["tools"].get(tool_name)`(없으면 None)
  4. 주소(`POLICY_URL_ENV`)와 토큰이 없으면 `POLICY_BLOCK_MESSAGE` 로 막는다
  5. 뿌리 session 을 `root_session` 으로 찾는다. `sqlite3.Error` 면 `build_context` 처럼 그 session 을 뿌리로 쓴다
  6. 본문 `{"v": 1, "root_session_id", "session_id", "tool_call_id", "hermes_tool", "tool", "args_json", "sig"}` 를 `POLICY_TIMEOUT` 으로 한 번 POST 한다. 응답 본문을 읽어야 하므로 `_post` 와 따로 `_post_json(url, body, token, timeout)` 을 두고 `(status, parsed_or_None)` 을 돌려준다
  7. 200 이고 `decision == "allow"` 이면 `None`. 200 이고 `decision == "block"` 이고 `message` 가 비지 않은 문자열이면 그 글로 막는다. 그 밖은 모두 `POLICY_BLOCK_MESSAGE` 로 막는다

`pre_tool_call` 을 고친다. 순서가 중요하다.

1. `skill_manage` 는 지금처럼 막는다
2. `tool_name` 이 문자열이 아니면 `None`
3. `TOOL_PREFIX` 로 시작하면 지금의 `_fos_ctx` 처리를 한다
4. 대응 파일을 읽는다. 없으면(`None`) `None` 을 돌려준다. 여기까지가 지금과 같은 동작이다
5. 읽지 못했으면(`ValueError`, `OSError`) `tool_name` 이 `MCP_PREFIX` 로 시작하거나 `CODE_EXECUTION_TOOL` 일 때 `POLICY_BLOCK_MESSAGE` 로 막고, 그 밖은 `None`
6. `tool_name == CODE_EXECUTION_TOOL` 이면 `CODE_EXECUTION_MESSAGE` 로 막는다
7. `MCP_PREFIX` 로 시작하지 않으면 `None`(예: `vision_analyze`)
8. `connector_policy(...)` 를 `try` 안에서 부른다. 예외는 종류만 `logger.warning` 으로 남기고 `POLICY_BLOCK_MESSAGE` 로 막는다

토큰, 서명, 인자, 응답 본문을 로그에 남기지 않는다. 모듈 docstring 에 「커넥터 정책」 절을 더해 위 규칙과 계약의 정본이 `docs/connectors.md` 의 「도구 호출 판정」 임을 적는다. `register` 의 로그 글에 정책 질의를 더한다.

`hermes/plugins/fos-ctx/plugin.yaml` 의 `version` 을 한 단계 올린다(지금 값에서 minor 를 올린다).

### 2. 이 phase 를 검증하는 `hermes/tests/test_fos_ctx.py`

`ConnectorPolicyTest(PluginFixture)` 를 더한다. 가짜 정책 서버는 `SubagentRegistrationTest` 의 `http.server` 방식을 쓰고, 받은 요청(헤더, 본문)을 기록하고 정해 둔 응답을 준다. 대응 파일은 임시 home 에 쓴다.

| 상황 | 기대 |
| --- | --- |
| 대응 파일 없음, `mcp__demo__write_note` | `None`. 서버 호출 없음 |
| 대응 파일 있음, 서버가 `allow` | `None`. 요청의 `hermes_tool`, `tool`, `args_json`, `Authorization` 이 맞고 `sig` 가 `sign_policy` 와 같다 |
| 서버가 `{"decision": "block", "message": "막음"}` | `{"action": "block", "message": "막음"}` |
| 서버가 `{"decision": "block"}`(글 없음) | `POLICY_BLOCK_MESSAGE` 로 막는다 |
| 서버가 `{"decision": "block", "message": ""}` | `POLICY_BLOCK_MESSAGE` 로 막는다 |
| 서버가 500 | 막는다. 요청은 한 번뿐 |
| 서버가 JSON 이 아닌 글 | 막는다 |
| 서버가 `POLICY_TIMEOUT` 보다 늦다(테스트는 `POLICY_TIMEOUT` 을 0.2 로 줄인다) | 막는다 |
| `FOS_CTX_POLICY_URL` 없음 | 막는다 |
| 토큰 없음(`get_secret` 이 None) | 막는다 |
| 대응 파일에 없는 도구 `mcp__demo__hidden` | 서버에 `tool: null` 로 묻는다 |
| `prefix` 가 맞는 서버가 없는 `mcp__other__x` | `UNKNOWN_SERVER_MESSAGE` 로 막는다. 서버 호출 없음 |
| `session_id` 빔 | `CONTEXT_BLOCK_MESSAGE`. 서버 호출 없음 |
| `tool_call_id` 빔 | 같다 |
| `execute_code` | `CODE_EXECUTION_MESSAGE` |
| 대응 파일이 깨진 JSON, `mcp__demo__x` | 막는다 |
| 대응 파일이 깨진 JSON, `vision_analyze` | `None` |
| 대응 파일 있음, `vision_analyze` | `None` |
| 대응 파일 있음, `mcp__fos_assistant__agent_list` | 지금처럼 `modify` 와 `_fos_ctx` |
| 대응 파일 있음, `skill_manage` | 지금처럼 막는다 |
| `_read_token` 이 예외 | `POLICY_BLOCK_MESSAGE` 로 막고 예외가 밖으로 나오지 않는다 |
| `args` 에 한글과 중첩 dict | `args_json` 이 키 정렬, 공백 없음, 한글 그대로 |

`SignatureContractTest` 에 `sign_policy` 벡터를 더한다. 값은 `ConnectorPolicyRequestTest` 의 `VECTOR_*` 와 같아야 한다. 두 파일의 상수 옆에 서로를 가리키는 주석을 단다.

막는 결과의 `message` 가 늘 비지 않은 문자열인지 보는 단언 헬퍼를 두고 막는 경우마다 쓴다.

### 3. e2e 대역 `test/e2e/fake-hermes.ts`

- `export const CONNECTOR_TOOL_PROBE = "커넥터 도구 검사"` 를 더한다. run 의 입력이 이 글로 시작하면 그 뒤의 줄마다 `<등록 이름> <JSON 인자>` 를 읽어 차례로 정책 경로를 부른다
- `setConnectorPolicy(endpoint: string)` 를 `FakeHermes` 에 더한다. 주소는 시나리오가 준다
- 호출은 hook 과 같은 본문이다. 토큰은 `profileEnv(profile)` 의 `MCP_FOS_ASSISTANT_API_KEY`, session 은 그 run 의 `session_id`, `tool_call_id` 는 `connector-call-<run 번호>-<줄 번호>`, `tool` 은 등록 이름이 `mcp__demo__` 로 시작하고 `DEMO_CONNECTOR.tools` 에 그 뒤 이름이 있으면 그 이름이고 아니면 `null` 이다. 서명은 `test/e2e/mcp-context.ts` 에 더한 `signedPolicyRequest(token, hermesTool, tool, rootSessionId, sessionId, toolCallId, argsJson)` 로 만든다
- 답이 `allow` 이면 대역 커넥터 서버가 불린 것으로 치고 `connectorToolCalls` 에 `{ profile, hermesTool, argsJson }` 를 더한다. `block` 이면 더하지 않는다. `FakeHermes` 에 `connectorToolCalls()` 를 더한다
- run 의 출력은 줄마다 `<등록 이름>: allow` 나 `<등록 이름>: block <message>` 다
- 토큰이 없거나 `setConnectorPolicy` 가 불리지 않았으면 `block` 으로 친다

### 4. e2e 시나리오 `test/e2e/scenarios/connector-policy.ts`

`export const connectorPolicyScenario: Scenario`, 이름 「커넥터 도구 정책」. `connectorScenario` 가 해제로 끝나므로 이 시나리오가 다시 등록하고 `/check` 로 `READY` 를 만든 뒤 끝에서 해제한다. 정리는 `try/finally` 로 한다.

`context.hermes.setConnectorPolicy(context.api.replace(/\/api\/v1$/, "") + "/internal/hermes/connector-policy")` 를 먼저 건다.
연결용 에이전트와의 대화는 연결 상태의 `agentCode` 로 `/chat/messages` 에 보낸다.

| 보낸 도구 | 기대 |
| --- | --- |
| `mcp__demo__list_scopes {}` | 출력에 `allow`. `connectorToolCalls()` 에 하나 |
| `mcp__demo__write_note {"text":"안녕"}` | `allow`(이 PR 은 기록만 한다). `connectorToolCalls()` 에 들어감 |
| `mcp__demo__hidden_tool {}` | `block`. `connectorToolCalls()` 에 없음 |
| `mcp__demo__purge_notes {}` | `block`. `connectorToolCalls()` 에 없음 |
| `context.hermes.setPolicyHook(profile, false)` 와 `/check` 로 `PENDING` 을 만든 뒤 `mcp__demo__list_scopes {}` | 연결용 에이전트가 꺼져 대화 자체가 거절되는지, 대화가 되면 `block` 인지 실제 응답을 보고 단언을 고정한다. 어느 쪽이든 `connectorToolCalls()` 가 늘지 않는다 |

`test/e2e/run.ts` 에 import 를 더하고 `SCENARIOS` 에서 `connectorScenario` 바로 뒤에 넣는다.

### 5. `hermes/README.md` 와 `docs/` 대조

`hermes/README.md` 의 fos-ctx 절에 운영 값 `FOS_CTX_POLICY_URL` 과 연결용 profile 에서의 동작 표를 더한다.
구현이 `docs/connectors.md` 의 「도구 호출 판정」 표와 다르면 멈추고 보고한다.

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- 서명 벡터가 있는 Java 테스트는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyRequestTest.java` 다
- 연결용 profile 에는 Control Plane MCP 가 등록돼 있지 않다. `TOOL_PREFIX` 갈래는 그대로 두되, 연결용 profile 의 판정 순서는 위 작업 항목 그대로다
- e2e 표의 마지막 줄을 이렇게 고정한다. `PENDING` 이 되면 연결용 에이전트가 꺼진다. 그 에이전트로 보낸 대화는 거절되고(꺼진 에이전트에 보낼 때의 상태 코드는 `ChatService` 의 경로를 읽고 단언한다) `connectorToolCalls()` 가 늘지 않는다

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
(cd backend && ./gradlew test)
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- 모두 종료 코드 0
- `hermes/tests/test_bundle.py` 가 묶음 안의 `fos-ctx` 를 원본과 견주면 그대로 통과해야 한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/fos-ctx/plugin.yaml` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `hermes/README.md` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/mcp-context.ts` | 수정 |
| `test/e2e/scenarios/connector-policy.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyRequestTest.java` | 수정 |
| `docs/connectors.md` | 수정 |
