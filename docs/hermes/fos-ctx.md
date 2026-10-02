# _fos_ctx 와 session 등록

## Control Plane 의 MCP 도구로 다른 실행을 부를 때

`memory_read` 를 두고 있는 그 자리에 실행을 시작하는 도구를 하나 더 두는 구조를
같은 날 측정했다. 측정용 MCP 서버를 하나 띄워 Hermes 가 그것을 부르게 하고,
그 서버가 다시 Runs API 를 부르게 했다.

### 도구 호출에는 실행을 가리키는 값이 없다

`tools/call` 요청이 들고 온 것은 우리가 설정에 적은 `Authorization` 헤더와
MCP 규약 헤더뿐이었다. `params._meta` 는 빈 객체였다.

```json
{"name": "echo_context", "arguments": {"note": "hello"}, "_meta": {}}
```

**도구가 아는 것은 어느 profile 이 불렀는지까지다.** 어느 실행에서 왔는지는 오지 않는다.
그러므로 이 경로로 만든 실행을 부모 `agent_execution` 에 잇는 값은 우리가 만들어야 한다.

### 부모 실행을 잇는 방법

2026-09-29 에 v0.21.5(`v2026.9.24`) 격리 환경에서 측정했다. 결정은 [ADR-031](../adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md) 에 있다.

**MCP 호출에 run 맥락을 실을 수 있는 공개 경로는 profile 플러그인의 `pre_tool_call` hook 하나다.**

| 경로 | 쓸 수 있나 | 근거 |
| --- | --- | --- |
| `tools/call` 의 `_meta`, HTTP 헤더 | 없다 | 도구 호출은 `tools/mcp_tool_handlers.py` 의 `call_tool(tool_name, arguments=args)` 한 곳이고 `meta` 를 넘기지 않는다. 헤더는 연결할 때 한 번 정해진다(`tools/mcp_tool_transport.py`) |
| `Mcp-Session-Id` | 없다 | 연결 하나의 값이다. 연결은 profile 당 하나를 모든 run 과 하위 에이전트가 함께 쓴다(`tools/mcp_tool_scope.py` 의 `_server_key`) |
| 설정의 `${VAR}` | 없다 | 설정을 읽을 때 한 번만 푼다(`tools/mcp_tool_config.py` 의 `_interpolate_env_vars`) |
| `/v1/runs` 본문 | 없다 | `provider`, `model`, `model_options` 만 agent 에 가고 LLM 요청에만 쓰인다 |
| `pre_tool_call` hook | 된다 | hook 은 `tool_name`, `args`, `task_id`, `session_id`, `tool_call_id` 를 받고 `{"action": "modify", "args": {...}}` 로 인자를 덮어쓴다(`hermes_cli/plugins.py`, `agent/tool_executor.py`) |

실측한 것이다.

| 확인 | 결과 |
| --- | --- |
| hook 이 넣은 키가 MCP `arguments` 에 도착한다 | 도착한다. Hermes 는 hook 이 더한 키를 도구 입력 규격으로 검증하지 않는다 |
| 모델이 같은 키를 넣었을 때 | hook 의 값이 이긴다 |
| hook 에서 그 profile 의 비밀값 읽기 | `get_secret` 으로 scope 오류 없이 읽힌다 |
| 같은 profile 에서 run 둘을 동시에 | 호출마다 자기 run 의 session 이 붙고 섞이지 않는다 |
| `/v1/runs` 에 Hermes 가 모르는 `session_id` 를 준 첫 run | 그 id 로 session 이 생긴다. 같은 id 로 보낸 다음 run 이 이어진다 |
| `delegate_task` 하위 에이전트의 호출 | session id 가 부모와 다르다. 하위 session 은 `parent_session_id` 로 부모를 가리킨다 |
| 압축 | 기본값 `compression.in_place: true` 에서는 session 이 바뀌지 않는다. `false` 이면 run 도중 새 session 으로 바뀐다 |
| `parent_session_id` 사슬을 따라 처음 session 찾기 | 1ms 미만. 하위 에이전트와 압축 교체 모두 처음 session 에 닿는다 |

그래서 hook 이 서명하는 값은 그 호출의 session 이 아니라 **사슬의 처음 session(뿌리 session)** 이다.

**플러그인은 profile 마다 둔다.** profile 디렉터리의 `plugins/` 에 두고 그 profile 설정에서 켜야 그 profile 의 호출에 붙는다. plugin 원본은 이 저장소의 [`hermes/plugins/fos-ctx/`](../../hermes/plugins/fos-ctx/) 에 있고, 대시보드 plugin 이 새 profile 을 만들 때 그 profile 로 복사한다.

**hook 이 끼우지 못한 호출도 서버에 도착한다.** 플러그인이 빠졌거나 hook 이 값을 돌려주지 않으면 원래 인자 그대로 간다. 그래서 서버는 서명이 없거나 틀린 호출을 거절한다. `memory_read`, `artifact_write`, `agent_*` 가 모두 그렇다([ADR-032](../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)).

#### `_fos_ctx` 계약

hook 은 Control Plane MCP 의 모든 도구 인자에 `_fos_ctx` 를 덮어쓴다.

| 키 | 값 |
| --- | --- |
| `v` | `1` |
| `session_id` | 그 호출의 session |
| `root_session_id` | `parent_session_id` 사슬의 처음 session |
| `tool_call_id` | 그 도구 호출의 id. Hermes 의 재시도에도 같다 |
| `sig` | 아래 서명의 소문자 16진수 |

서명은 HMAC-SHA256 이다.

- key 는 **그 profile 의 MCP 토큰을 SHA-256 한 값의 소문자 16진수 문자열**이다. 서버는 토큰의 원문 대신 이 해시를 저장하므로 같은 key 를 갖는다
- 서명할 글은 `v1`, 도구 이름(서버 쪽 이름. 예: `agent_delegate`), `root_session_id`, `session_id`, `tool_call_id` 를 이 순서로 줄바꿈(`\n`) 하나로 이은 것이다

인코딩은 아래와 같다. 플러그인도 같은 규칙으로 서명한다.

- HMAC key 는 64자 소문자 16진수 문자열의 **UTF-8 바이트**다. 16진수를 풀어 낸 32바이트가 아니다. 서명할 글도 UTF-8 이다
- `v` 는 JSON 숫자 `1` 이다. 문자열 `"1"` 은 거절한다
- `sig` 는 소문자 16진수 64자만 받는다. 대문자가 섞이면 거절한다
- 서명할 글에 도구 인자는 넣지 않는다

서명을 맞춰 보는 값이다. Python `hmac` 으로 계산했고 구현과 무관한 기대값이다. 토큰은 가짜 값이다.

| 항목 | 값 |
| --- | --- |
| 토큰 원문 | `test-mcp-token-0001` |
| key(토큰의 SHA-256 소문자 16진수) | `41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac` |
| 도구 이름 | `agent_delegate` |
| `root_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| `session_id` | `하위-세션-1` |
| `tool_call_id` | `call_0001` |
| 기대 `sig` | `b28a128dbb642aba7a8b4c35dcb237e2feb5a452305ec275909c32a00ae1b25b` |
| 같은 칸에 도구 이름만 `agent_status` 일 때 | `62109c6c99e7ed4638e4343f1e5b6b22a3f55dd974560916149986866c253236` |

서버는 모든 도구에서 `_fos_ctx` 로 요청자를 정한다. 서명을 확인한 뒤 차례로 본다.

1. 토큰이 증명한 profile 과 그 호출의 `session_id` 로 하위 에이전트 등록을 찾는다. 있으면 그 origin 실행의 사용자로 돈다. origin 실행이 끝났어도 된다. origin 실행이나 그 실행 나무의 뿌리 실행이 `CANCELLED` 면 거절한다
2. 등록이 없고 `session_id` 가 `root_session_id` 와 같으면, 그 profile 과 뿌리 session 으로 도는 실행 하나를 찾아 그 실행의 사용자로 돈다
3. 등록이 없고 `session_id` 가 뿌리와 다르면 거절한다

결정은 [ADR-037](../adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.
도구 인자 검사에 넘기기 전에 `_fos_ctx` 는 떼어 낸다. 도구 규격이 그 키를 모르기 때문이다.
플러그인에 요구하는 동작이다. 서명할 수 없으면 `memory_read`, `artifact_write`, `agent_*` 를 모두 Hermes 쪽에서 막는다. 플러그인은 이 저장소의 `hermes/plugins/fos-ctx/` 가 갖고 `hermes/tests/test_fos_ctx.py` 가 그 동작을 검사한다. 다만 profile 에 실제로 설치되어 켜졌는지는 이 저장소가 확인하지 못한다. 그래서 서버도 서명이 없는 호출을 거절한다. 플러그인이 막지 못해도 서버에서 같은 조건으로 막힌다.

#### 호출은 profile 마다 하나씩 나간다

같은 profile 의 MCP 연결 하나가 `_rpc_lock` 으로 호출을 직렬로 보낸다(`tools/mcp_tool.py`).
한 run 의 도구 호출이 오래 걸리면 같은 profile 의 다른 run 이 기다린다.
그래서 `agent_delegate` 는 제출까지만 기다리고, `agent_status` 는 저장된 값만 읽는다.

#### 재시도와 `tool_call_id`

Hermes 는 401 이면 다시 연결해 같은 인자로 한 번 더 보낸다. session 이 만료되면 읽기 전용 도구만 다시 보내고 쓰기 도구는 `outcome_uncertain` 으로 끝낸다.
hook 이 넣은 `tool_call_id` 는 인자에 들어 있어 다시 보낼 때도 같다. 그래서 **profile, 뿌리 session, 그 호출의 session, `tool_call_id`** 로 같은 위임을 두 번 만들지 않는 키를 계산한다.
`tool_call_id` 가 뿌리 아래 모든 session 에서 유일하다는 보장은 없다. 하위 에이전트마다 session 이 달라, session 을 빼면 다른 하위 에이전트의 같은 번호가 같은 위임으로 잘못 합쳐진다. 정의는 [ADR-032 의 「`delegation_key`」](../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md#delegation_key) 에 있다.
JSON-RPC 의 `id` 는 연결마다 새로 매겨져 이 용도로 쓰지 않는다.

#### 공유 profile 에서 두 사용자의 호출을 실제로 확인하는 절차

가짜 Hermes 검사는 플러그인을 흉내 내므로, 실제 Hermes 와 실제 플러그인에서 한 번 더 확인한다.
환경과 실행 방법은 `fos-home-infra` 가 갖는다. 여기에는 무엇을 보고 무엇이 나와야 하는지만 적는다.

준비할 것이다.

- Hermes v0.21.5 와 그 profile 에 켠 플러그인. 그 profile 을 가리키는 GROUP 에이전트 하나
- 그 profile 에 묶인 토큰. 사용자 A 와 B 가 각자 볼 수 있는 USER Memory 하나씩. 둘 다 제목만 싣는 색인 항목이다
- 플러그인의 `pre_tool_call` 이 받은 `session_id`, 사슬로 찾은 `root_session_id`, `tool_call_id` 를 로그에 남기게 한 상태. 서명과 토큰은 남기지 않는다

확인할 것이다.

| 순서 | 할 것 | 기대하는 것 |
| --- | --- | --- |
| 1 | A 와 B 가 각자 새 대화에서 같은 에이전트로 자기 Memory 본문을 묻는다. 두 run 이 겹쳐 돌게 한다 | 두 run 의 `root_session_id` 가 서로 다르고, 각자 그 대화의 `conversation.hermes_root_session_id` 와 같다 |
| 2 | 같은 두 run 의 `pre_tool_call` 로그를 본다 | 한 run 의 모든 호출에서 `root_session_id` 가 같다. 다른 run 의 값이 섞이지 않는다 |
| 3 | 두 답을 본다 | A 의 답에는 A 의 본문만, B 의 답에는 B 의 본문만 있다 |
| 4 | Control Plane 로그의 `memory read` 줄을 본다 | 호출마다 `userId` 가 그 run 의 실행 줄 `user_id` 와 같고 `executionId` 가 그 run 의 실행 줄이다 |
| 5 | A 가 `delegate_task` 를 쓰게 해 하위 에이전트가 `memory_read` 를 부르게 한다 | 하위 에이전트 호출의 `session_id` 는 부모와 다르고 `root_session_id` 는 A 의 대화 뿌리와 같다. 결과는 A 의 본문이다. 부모 run 이 끝난 뒤에 불러도 같다 |
| 6 | 플러그인을 끈 profile 에서 같은 질문을 한다 | `memory_read` 가 「호출 맥락을 확인할 수 없습니다」 로 끝나고 본문은 오지 않는다 |

1 과 2 가 어긋나면 서버 쪽 판정이 옳아도 사용자가 섞인다. 그때는 배포를 되돌리지 말고 그 profile 의 토큰 묶기를 미루고 원인을 조사한다.

### 하위 에이전트는 부모 run 보다 오래 산다

2026-09-29 에 v0.21.5(`v2026.9.24`, commit `f97608f178d1ffeca59860195ab7da295f7c8e5f`)의 소스를 읽어 확인했다. 실제 실행으로 확인하는 절차는 아래 「하위 에이전트를 실제로 확인하는 절차」 에 있다.

| 확인한 것 | 근거 |
| --- | --- |
| 모델이 부르는 최상위 `delegate_task` 는 늘 background 로 요청된다. 깊이 1 이상의 오케스트레이터 자식이 부르면 동기다. 도구 규격의 `background` 인자는 무시한다 | [`run_agent.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/run_agent.py) 의 `_dispatch_delegate_task` |
| background 가 실제로 떨어져 나가는지는 `_resolve_async_wake_sid` 가 정한다. API server 는 비동기 전달을 지원하지 않지만, 그 run 이 session 이력으로 결과를 받는 run 이면 자식이 떨어져 나가고 결과는 다음 turn 을 위해 session 이력에 저장된다 | [`tools/delegate_tool_dispatch.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_dispatch.py) 의 `_resolve_async_wake_sid` |
| `/v1/runs` 가 `conversation_history` 와 `previous_response_id` 없이 오면 session 이력으로 결과를 받는 run 이다 | [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `session_history_delivery` |
| `subagent_start` hook 은 자식을 만드는 `_build_children` 안에서 부모 스레드가 부른다. 인자는 `parent_session_id`, `parent_turn_id`, `parent_subagent_id`, `child_session_id`, `child_subagent_id`, `child_role`, `child_goal` 이다 | [`tools/delegate_tool.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool.py) 의 `_build_child_agent` 끝. `_build_children` 이 자식마다 부른다 |
| 그 hook 은 제한 시간 목록에 없어 끝날 때까지 기다린다. 자식은 `_build_children` 이 모든 자식을 만든 뒤에야 `_run_batch` 로 돌기 시작한다 | [`hermes_cli/plugins_dispatch.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins_dispatch.py) 의 `_HOOK_TIMEOUT_BOUNDED_HOOKS`, `delegate_task` 의 호출 순서 |
| hook 이 예외를 던져도 Hermes 는 삼키고 자식을 그대로 돌린다 | 같은 자리의 `_quiet("subagent_start hook invocation failed")` |
| 압축 교체에는 plugin hook 이 없다 | [`hermes_cli/plugins.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py) 의 `VALID_HOOKS` |

Control Plane 은 `/v1/runs` 에 `session_id` 만 보낸다. 그래서 **모델이 부른 하위 에이전트는 부모 FOS 실행이 끝난 뒤에도 돈다.**
부모 실행의 `RUNNING` 으로 요청자를 찾으면 그 뒤의 호출은 거절되거나 같은 대화의 다음 turn 에 붙는다.
그래서 하위 에이전트 session 은 만들어질 때 등록한다([ADR-037](../adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).

**등록이 첫 MCP 호출보다 먼저 끝나는 근거는 hook 이 동기라는 것이다.** 자식은 hook 이 돌아온 뒤에 시작하므로 자식의 첫 도구 호출은 등록 응답 뒤에 온다.
실제 실행에서 시각으로 한 번 더 확인한다. 등록이 늦거나 실패하면 그 자식의 호출은 거절된다. 추측으로 이어 붙이지 않는다.

추측과 확인하지 않은 것이다.

- 오케스트레이터 자식이 동기로 만든 자식도 같은 `_build_children` 을 지나므로 hook 이 불린다고 본다. 실제 실행으로 확인하지 않았다
- 하위 에이전트의 압축 교체는 자식의 `parent_session_id` 사슬을 늘린다고 본다. 교체된 하위 에이전트 session 은 등록이 없어 거절된다

#### 하위 에이전트 session 등록 계약

profile 플러그인이 `subagent_start` hook 에서 부른다. 모델 도구가 아니고 도구 목록에 나오지 않는다.

| 항목 | 값 |
| --- | --- |
| 경로 | `POST /internal/hermes/session-bindings/subagent` |
| 인증 | `Authorization: Bearer <그 profile 의 MCP 토큰>`. `/mcp` 와 같은 토큰이다. profile 이 빈 토큰은 인증에서 거절한다 |
| 본문 | JSON 객체. 아래 칸 |
| 제한 시간 | 플러그인이 짧게 둔다(예: 3초). 한 번만 다시 보낸다 |

| 칸 | 값 |
| --- | --- |
| `v` | JSON 정수 `1` |
| `parent_session_id` | hook 이 받은 `parent_session_id` |
| `parent_root_session_id` | `parent_session_id` 사슬의 처음 session. `pre_tool_call` 이 계산하는 뿌리와 같다 |
| `child_session_id` | hook 이 받은 `child_session_id` |
| `child_subagent_id` | hook 이 받은 값. 없으면 `null`. 서명하지 않고 로그에만 쓴다 |
| `parent_subagent_id` | hook 이 받은 값. 최상위 자식이면 `null`. 서명하지 않고 로그에만 쓴다 |
| `sig` | 아래 서명의 소문자 16진수 64자 |

서명은 `_fos_ctx` 와 같은 HMAC-SHA256 이고 key 도 같다. 서명할 글만 다르다.

- key 는 그 profile 의 MCP 토큰을 SHA-256 한 소문자 16진수 64자 문자열의 **UTF-8 바이트**다
- 서명할 글은 `v1-subagent`, `parent_root_session_id`, `parent_session_id`, `child_session_id` 를 이 순서로 줄바꿈(`\n`) 하나로 이은 UTF-8 이다
- 첫 줄이 도구 이름 자리와 달라 `_fos_ctx` 서명을 등록 서명으로 쓸 수 없다

| 항목 | 값 |
| --- | --- |
| 토큰 원문 | `test-mcp-token-0001` |
| `parent_root_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| 최상위 자식: `parent_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| 최상위 자식: `child_session_id` | `하위-세션-1` |
| 최상위 자식의 기대 `sig` | `ba540b481d830453accd812c8610be8d9467a532d5ff3db891514dcfe3d0726b` |
| 중첩 자식: `parent_session_id` | `하위-세션-1` |
| 중첩 자식: `child_session_id` | `하위-세션-2` |
| 중첩 자식의 기대 `sig` | `5479a21f26ddeb337754d4fd86dd3a0e36e0ef1f6ff2cc879c0fd07da5d84485` |

서버는 서명을 확인한 뒤 같은 `child_session_id` 의 줄을 먼저 본다. 있고 뿌리와 부모가 요청과 같으면 부모를 풀지 않고 성공으로 답한다. 첫 응답을 잃고 다시 보내는 사이 부모 run 이 끝날 수 있어서다. 그 밖에는 부모를 푼다.

1. `(토큰의 profile, parent_session_id)` 등록이 있으면 그 origin 을 잇는다. 하위 에이전트의 하위 에이전트다
2. 없으면 `(토큰의 profile, parent_root_session_id)` 로 도는 실행 하나가 origin 이다. 최상위 session 이 만든 자식이고, 압축 교체된 최상위 session 이 만든 자식도 뿌리가 같아 여기 온다
3. 둘 다 없으면 거절한다

| 응답 | 뜻 |
| --- | --- |
| `201` `{"result": "created"}` | 새로 등록했다 |
| `200` `{"result": "exists"}` | 같은 부모와 뿌리로, 또는 같은 origin 으로 이미 등록돼 있다. 다시 보낸 것으로 본다. 앞의 경우 부모를 다시 풀지 않는다 |
| `401` | 토큰이 없거나 모르는 토큰이거나 폐기됐거나 profile 이 빈 토큰이다. 사용자 JWT 로 불러도 같다. 본문이 없다 |
| `403` `{"code": "SESSION_BINDING_REJECTED"}` | JSON 이 아니거나 모양이 틀린 본문, 서명, 부모를 풀지 못함, session 값이 128자를 넘음, `child_session_id` 가 부모나 뿌리나 실행 줄이나 대화의 session 과 같음. 이유는 서버 로그에만 남는다 |
| `409` `{"code": "SESSION_BINDING_CONFLICT"}` | 그 `child_session_id` 가 다른 origin 으로 이미 등록돼 있다. 덮어쓰지 않는다 |

플러그인은 2xx 가 아니거나 연결하지 못하면 로그만 남기고 hook 을 돌려준다. 등록이 없는 하위 에이전트의 호출은 서버가 거절하므로 안전한 쪽으로 실패한다.
플러그인과 서버 모두 토큰, `sig`, 본문을 로그에 남기지 않는다.

#### 모든 토큰이 profile 에 묶여 있다

등록과 origin 판정, 아래 「취소가 아래로 내려가지 않는다」 의 취소 차단은 토큰이 profile 을 증명하는 것을 전제로 한다.
`agent_token` 에는 사용자 칸이 없고, 폐기되지 않은 토큰은 늘 `profile_name` 을 갖는다. profile 이 빈 토큰은 `/mcp` 와 등록 경로 모두 인증에서 `401` 이다.

전에는 사용자 기준으로 발급한 옛 토큰이 설정이 허용할 때 `_fos_ctx` 를 보지 않고 그 토큰의 사용자로 돌았다.
그 profile 에서는 origin 실행을 찾지 않아, 부모 turn 을 중지해도 하위 에이전트의 호출이 막히지 않았다.
운영의 모든 토큰을 profile 에 묶은 뒤 그 경로를 지웠다. 순서와 근거는 [ADR-032](../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 의 「옛 토큰에서 옮겨 가는 길」 에 있다.

#### 하위 에이전트를 실제로 확인하는 절차

환경과 실행 방법은 `fos-home-infra` 가 갖는다. 일회용 Hermes v0.21.5 에서 본다.

| 순서 | 할 것 | 기대하는 것 |
| --- | --- | --- |
| 1 | 플러그인이 `subagent_start` 를 받았는지 본다 | hook 이 불리고 `parent_session_id`, `child_session_id` 가 채워져 있다 |
| 2 | `delegate_task` 를 부르는 최상위 run 을 돌린다 | 부모 run 이 먼저 끝나고 자식은 계속 돈다 |
| 3 | 등록 응답 시각과 자식의 첫 도구 호출 시각을 로그로 남긴다 | 등록 응답이 먼저다 |
| 4 | 부모 run 이 끝난 뒤 자식이 `memory_read` 를 부른다 | origin 실행의 사용자의 본문이 온다 |
| 5 | `compression.in_place: false` 로 교체된 최상위 session 에서 자식을 만든다 | 등록 본문의 `parent_root_session_id` 가 원래 뿌리다 |
| 6 | 플러그인의 등록을 끈 채 자식이 `memory_read` 를 부른다 | 「호출 맥락을 확인할 수 없습니다」 로 끝난다 |
| 7 | 자식이 도는 동안 사용자가 부모 turn 을 중지하고, 그 뒤 자식이 `memory_read` 를 부른다 | origin 실행이 `CANCELLED` 이고 「호출 맥락을 확인할 수 없습니다」 로 끝난다 |

Hermes 를 올릴 때도 이 표를 다시 돌린다. `subagent_start` 가 사라지거나 인자 이름이 바뀌면 하위 에이전트의 MCP 호출이 모두 거절된다.
