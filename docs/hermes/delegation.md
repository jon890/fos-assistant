# 위임

## 내장 delegation 이 실제로 하는 것

2026년 9월 18일에 v0.21.0 배포본에서 측정했다.
운영 profile 을 건드리지 않으려고 컨테이너 안에 격리된 `HERMES_HOME` 을 따로 만들고
그 아래 측정용 profile 로만 실행했다. 측정이 끝난 뒤 만든 것을 모두 지웠다.

`delegate_task` 가 그 도구다. 부모 실행이 이 도구를 부르면 Hermes 가 자식 agent 를 만든다.

### 부모의 `instructions` 는 자식에게 가지 않는다

**Control Plane 이 `instructions` 로 주입한 Memory 는 subagent 로 넘어가지 않는다.**

부모에게 `instructions` 로 `MEMORYMARK-7731` 로 시작하는 줄 하나를 주고
부모와 자식에게 같은 질문을 던졌다.

| 누구에게 물었나 | 답 |
| --- | --- |
| 부모 | `MEMORYMARK-7731: the user is allergic to peanuts.` |
| 자식 | `NOMARK` |

`tools/delegate_tool.py` 의 `_build_child_agent` 가 자식을 `skip_memory=True` 와
`skip_context_files=True` 로 만들고 자식 system prompt 를 goal 로 새로 쓰기 때문이다.
소스와 실행이 같은 결과를 낸다.

자식의 도구는 `_build_child_agent` 에서 부모 toolset 과의 교집합만 받고,
`DELEGATE_BLOCKED_TOOLS` 에 포함된 `memory` 등의 도구를 한 번 더 뺀다.

privacy 로는 이쪽이 안전하다. 부모에 넣은 개인 Memory 가 자식으로 새지 않는다.
대신 자식에게 무언가를 알려야 하면 goal 본문에 직접 적어야 하고,
그 본문은 Control Plane 이 무엇을 담을지 정해야 한다.

### 자식 도구의 허용 범위

2026-09-28 에 v0.21.0 의 `tools/delegate_tool.py` 의 `_build_child_agent` 를 읽어 확인했다.

| 항목 | 동작 |
| --- | --- |
| toolset | 모델이 지정한 목록도 부모의 `enabled_toolsets` 와 교집합을 만든다. 부모에 없는 `terminal`, `file` 을 받지 못한다 |
| 항상 제외하는 도구 | `DELEGATE_BLOCKED_TOOLS` 의 `delegate_task`, `clarify`, `memory`, `send_message`, `cronjob_manage` |
| orchestrator 예외 | `delegation` 을 다시 넣는다. 위임 시작에는 부모의 `delegation` 이 필요하고, 깊이는 `delegation.max_spawn_depth` 가 제한한다 |
| `disabled_toolsets` | 부모 목록을 물려준다. orchestrator 는 여기서 `delegation` 만 뺀다 |
| MCP | `delegation.inherit_mcp_toolsets` 가 참이면 부모 MCP toolset 을 유지한다. 기본값은 참이다 |
| 기억·문맥 파일 | `skip_memory=True`, `skip_context_files=True` 로 자식을 만든다 |
| 위험한 명령 | `delegation.subagent_auto_approve` 가 거짓이면 자식 스레드에 자동 거절 콜백을 건다. gateway 세션은 승인 큐를 쓴다는 주석이 있다 |

API server 의 `_create_agent` 는 `disabled_toolsets` 를 따로 넘기지 않지만,
이미 제외한 도구 목록을 `enabled_toolsets` 로 주므로 자식의 교집합도 같은 범위를 지킨다.
부모의 `approvals.mode: off` 가 자식에게 그대로 적용되는지와 자식 스킬 색인 구성은
이 조사에서 확인하지 못했다.
자식 system prompt 는 `_build_child_system_prompt` 가 별도로 만든다.

근거는 [v0.21.0 delegate_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/tools/delegate_tool.py) 다.

### 자식은 한 단계 더 자식을 만든다

**ADR-016 이 적은 「leaf 한 단계만 된다」 는 이 버전에서 맞지 않는다.**

배포 설정과 같은 `max_spawn_depth: 1` 과 `orchestrator_enabled: true` 로 두고
부모에게 `role=orchestrator` 로 자식을 만들게 한 뒤 그 자식이 다시 위임하도록 시켰다.
손자가 실제로 떴고 SSE 에 아래가 찍혔다.

```json
{"event": "subagent.start", "subagent_id": "sa-0-4cac90e1",
 "parent_id": "sa-0-58f24d06", "depth": 1,
 "child_session_id": "20260918_104940_bf9c03"}
```

자식이 `depth: 0`, 손자가 `depth: 1` 이고 `parent_id` 가 둘을 잇는다.
`max_spawn_depth` 는 상한이 없는 설정 값이라 더 깊게도 열 수 있다.

### 자식 토큰을 SSE 로 받을 수 있다

**ADR-016 이 「자식 토큰을 잃는다」 고 적은 근거가 이 버전에서는 바뀐다.**
부모 Runs API 의 `usage` 에 자식이 더해지지 않는 것은 그대로다.
다만 자식의 사용량이 사건으로 따로 나온다.

| 사건 | 함께 오는 칸 |
| --- | --- |
| `subagent.start` | `subagent_id`, `child_session_id`, `delegation_id`, `parent_id`, `depth`, `model`, `goal`, `task_index`, `task_count` |
| `subagent.complete` | 위의 것 전부와 `status`, `summary`, `duration_seconds`, `input_tokens`, `output_tokens`, `reasoning_tokens`, `api_calls`, `cost_usd`, `files_read`, `files_written` |

실측한 `subagent.complete` 하나다.

```json
{"status": "completed", "duration_seconds": 0.93,
 "input_tokens": 10337, "output_tokens": 68,
 "reasoning_tokens": 0, "api_calls": 1, "cost_usd": 0.0,
 "child_session_id": "20260918_104444_6c460e"}
```

**`cost_usd` 를 비용 근거로 쓰지 않는다.** 가격표가 없는 provider 에서는 0 으로 온다.
위 실행도 토큰은 맞게 왔지만 `cost_usd` 가 0 이었다. 토큰을 저장하고 비용은 우리가 환산한다.

`child_session_id` 로 `GET /api/sessions/{id}` 를 부르면 cache 토큰까지 나온다.
응답은 `object=hermes.session` 과 중첩된 `session` 객체이고, 아래는 그 안의 필드다.
`session.parent_session_id` 가 부모 session 을 가리킨다.
Runs API 가 처음 만든 session 은 첫 실행의 `run_id` 를 쓸 수 있지만,
이어지는 실행의 번호와 부모 session 번호를 같은 것으로 취급하면 안 된다.

```json
{"id": "20260918_103840_ac00e1", "source": "subagent",
 "input_tokens": 9193, "output_tokens": 79, "cache_read_tokens": 960,
 "reasoning_tokens": 75, "estimated_cost_usd": 0.0054140625,
 "parent_session_id": "run_e4f5549bd5b04545985670d3457d830c"}
```

v0.21.0 과 v0.21.3 Runs API 의 `usage` 에는 cache 칸이 없지만 이 경로에는 있다.

### 최상위 위임의 완료 사건은 부모 스트림으로 받지 못할 수 있다

**2026-09-28 에 Hermes v0.21.0 에서 다시 측정했다.**
`delegate_task` 는 `background` 인자를 무시하며, 그 인자는 도구 스키마에서도 빠져 있다.
최상위 위임은 항상 비동기로 돌고 **부모 실행이 자식보다 먼저 끝난다.**
그 시점에 SSE 가 닫혀 `subagent.start` 는 받지만 `subagent.complete` 와 자식 토큰은 받지 못한다.

2026-09-18 에 적은 「`background=false` 로 시킨 실행에서는 시작과 완료 사건이 모두 왔다」 는
이번 v0.21.0 측정에서 재현되지 않았다. 이 인자로 동기 위임을 보장할 수 없다.

**v0.21.0 실측에서는 실행의 자식 목록을 얻지 못했다.**
그 버전의 `GET /api/sessions` 는 `parent_session_id` 질의 인자를 무시하고
`source` 가 `subagent` 인 session 을 목록에서 제외한다.
`/v1/runs/{id}/subagents` 같은 경로도 없다. 404 다.
v0.21.3 과 v0.21.5 의 session 목록은 자식 포함 옵션과 source 필터를 지원한다.
조회 형식과 사용량 보완 방법은 아래 「자식 session 으로 결과와 토큰을 보완한다」 를 따른다.

그러므로 부모 SSE 를 끝까지 받아도 자식 사용량이 모두 기록된다고 보장할 수 없다.
완료 사건을 받지 못한 자식의 결과와 토큰은 모르는 값으로 남긴다.
부모가 끝났다는 이유로 자식이 중지됐다고 판정하거나 토큰을 0 으로 채우지 않는다.

### 공유 listener 의 완료 watcher 결함과 수정

2026-09-28 에 v0.21.0 배포본의 코드와 기존 기록을 읽어 원인을 확인했다.
HTTP 요청과 부모·자식 session 저장은 요청 profile 의 DB 를 쓴다.
그러나 완료 watcher 는 새 `Context` 에서 시작해 요청 profile 문맥을 잃는다.
그 결과 listener 주인 profile 의 DB 에서 부모를 찾고, 없으면 완료 결과를 버린다.

| 단계 | 근거 함수 |
| --- | --- |
| HTTP 요청 문맥 | `api_server.py` 의 `_make_profile_prefix_middleware`, `_profile_scope` |
| session 저장 DB | 같은 파일의 `_ensure_session_db`, `_open_and_cache_session_db`, `delegate_tool.py` 의 `_build_child_agent` |
| watcher 생성과 부모 판정 | `gateway/run.py` 의 `_spawn_supervised`, `_async_delegation_watcher`, `_deliver_completion_notification`, `_classify_completion_target` |
| 전달 상태 갱신 | `tools/async_delegation.py` 의 `_db_path`, `claim_completion_delivery`, `drop_completion_delivery` |

`get_hermes_home` 과 `hermes_state.py` 의 `_default_db_path` 는 활성 문맥으로 DB 를 고른다.
자식은 `propagate_context_to_thread` 로 문맥을 물려받아 올바른 DB 에 결과를 저장한다.
watcher 의 claim·drop 은 다른 DB 를 볼 수 있고, claim 은 행이 없으면 이전 형식 사건으로
간주해 성공을 반환한다.
따라서 로그에 폐기 경고가 있어도 원래 profile 의 전달 행은 `pending` 으로 남을 수 있다.
부모 session 이 실제로 삭제된 경우와 구분해야 한다.

**v0.21.3 은 완료 사건의 profile 문맥에서 부모 판정, claim, 결과 주입과 정산을 수행한다.**
시작할 때 보조 profile 의 미전달 기록도 복구한다.
근거는 [수정 commit `c632437c3bb3`](https://github.com/NousResearch/hermes-agent/commit/c632437c3bb3fcd19e755882ce346b270ef3d151) 과
[v0.21.3 run_notifications.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_notifications.py) 의
`_completion_event_scope`, `_deliver_async_delegation_group`, `_restore_secondary_completion_ledgers` 다.

v0.21.0 에서 공유 listener 를 유지하며 완료 watcher 만 profile 별로 고르는 설정은 확인하지 못했다.
별도 gateway 를 쓰면 기본 문맥과 요청 profile 이 같아져 결함을 피할 수 있다는 소스상 판단은 있지만,
회피안을 실제로 적용해 검증하지 않았다.
DB 를 합치는 방식은 profile 분리를 바꾸므로 회피안으로 삼지 않는다.
최상위 위임은 `_dispatch_delegate_task` 가 `background=True` 로 보내므로
모델에게 동기 위임을 요구해도 회피가 보장되지 않는다.

### API 위임 결과는 delivery 기록으로 남는다

2026-09-28 에 v0.21.0 배포본과 v0.21.3, v0.21.5 소스를 대조했다.
**API 비동기 위임 완료는 부모의 새 모델 turn 이나 새 run 을 자동으로 만들지 않는다.**
`APIServerAdapter.supports_async_delivery` 는 거짓이다.
완료 결과는 `_inject_watch_notification` 에서 `gateway/wake.py` 의
`persist_delegation_delivery` 로 가며, 부모 session 에 `role=user`,
`display_kind=async_delegation_complete` 인 메시지를 한 번 기록한다.
실제 사용자의 새 질문과 구분해야 하는 delivery 기록이다.

다음 클라이언트 turn 이 server history 를 읽을 때 이 결과를 문맥으로 사용한다.
일반 백그라운드 작업 알림의 `_self_post_chat_completion` 과 다른 분기다.
제품에서 부모의 이어 답을 원하면 Control Plane 이 새 run 을 명시적으로 제출하고 추적해야 한다.
upstream 이슈에서 자동 후속 답을 관찰했다는 기록만으로 API 경로도 그렇다고 판정하지 않는다.

v0.21.3 과 v0.21.5 에서 비동기 위임 여부는 session history 를 어떻게 쓰는지도 따른다.
Runs 가 server history 를 다시 읽는 session 을 만들면 `session_history_delivery` 를 전달한다.
`_resolve_async_wake_sid` 는 그 권한이 있는 raw session 에서 detached 결과를 허용한다.
caller 가 history 를 직접 주거나 response chain snapshot 을 쓰는 요청,
finite single-query 요청은 동기로 돌아갈 수 있다.
내부 변수 이름의 wake 는 API 모델 자동 실행을 뜻하지 않는다.

근거는 [v0.21.3 wake.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/wake.py),
[delegate_tool_dispatch.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/delegate_tool_dispatch.py),
[API 위임 계약 테스트](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tests/gateway/test_api_delegation_delivery_contract.py) 다.
조사에서 upstream 테스트를 읽었지만 실행하지는 않았다.

### 자식 session 으로 결과와 토큰을 보완한다

v0.21.0 배포본에서 원인을 조사하고 v0.21.3 과 v0.21.5 의 조회 형식도 확인했다.
부모 run 이 끝나면 종료 sentinel 이 SSE 를 닫고 queue 를 제거한다.
뒤늦은 자식 callback 이 원래 스트림을 복구하거나 완료 사건을 재생하는 API 는 없다.
완료한 run 조회 응답에 자식 결과를 덧붙이는 계약도 확인하지 못했다.
소비자는 별도 session 조회로 완료 표시와 사용량을 보완해야 한다.

| 경로 | 얻는 것과 한계 |
| --- | --- |
| `GET /api/sessions/{id}` | 중첩 `session` 의 모델, 부모, 종료 이유, 입력·출력·cache 토큰. `provider` 는 safe key 에 없다 |
| `GET /api/sessions/{id}/messages` | 자식 답 또는 부모에게 전달된 위임 결과. `display_kind` 는 나오지만 `display_metadata` 는 나오지 않는다 |
| `GET /api/sessions` | v0.21.3·v0.21.5 는 `include_children=true` 와 `source=subagent` 를 지원한다. 기본은 자식을 제외한다. 목록은 `object=list`, `data`, `limit`, `offset`, `has_more` 다 |
| 내부 위임 영구 기록 | `async_delegation.py` 의 `get_durable_delegation` 에 상태, 작업별 결과, 모델, 토큰과 소요 시간이 있다. 공개 HTTP endpoint 는 아니다 |
| 자식 로그·manifest | 답 일부와 종료 상태가 있으나 보관 기간과 글자 제한이 있어 영구 기록을 대신하지 못한다 |

메시지 목록은 `object=list`, 실제 continuation 의 `session_id`, `data`, `pagination` 이다.
기본 최신 500개이며 `order`, `limit`, `offset` 을 받는다.
부모 메시지의 delivery 식별자로 중복 수입을 막을 수 있지만,
메시지 API 만으로 작업별 위임 식별자와 토큰을 모두 얻을 수 있다고 가정하면 안 된다.
자식 session 의 `agent_close` 만으로 작업 성공을 단정하지 않는다.

**완료 사건의 입력 토큰과 session 입력 토큰은 합산 기준이 다르다.**
`delegate_tool.py` 의 `_run_single_child` 는 `session_prompt_tokens` 를 사건의 `input_tokens` 로 보낸다.
session 은 cache 를 뺀 `input_tokens` 와 cache read·write 를 따로 누적한다.
사건의 입력 토큰과 맞추려면 session 의 입력, cache read, cache write 를 합산한다.
비용 계산에서는 cache 토큰에 일반 입력 단가를 일괄 적용하지 않는다.
부모 usage 에 자식 usage 가 포함되지 않으므로 부모와 각 자식의 기록을 중복 없이 더한다.
조회하지 못한 사용량은 모르는 값으로 남긴다.

근거는 [v0.21.3 session 직렬화](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py) 의
`_session_response`, `_message_response`, `_handle_list_sessions` 와
`agent/conversation_loop.py`, `agent/turn_finalizer.py` 의 토큰 누적이다.

### 자식의 모델은 부모의 것이 아니다

부모를 `openai/gpt-oss-20b` 로 돌린 실행에서 자식이 `z-ai/glm-5.2` 로 돌았다.
자식 모델은 `delegation.provider` 와 `delegation.model` 이 정하고,
비어 있으면 부모가 아니라 별도 기본값으로 떨어진다.
실행별 비용을 보려면 이 값을 명시해야 한다.

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

**플러그인은 profile 마다 둔다.** profile 디렉터리의 `plugins/` 에 두고 그 profile 설정에서 켜야 그 profile 의 호출에 붙는다. 배치 방법은 비공개 저장소 `fos-home-infra` 가 갖는다.

**hook 이 끼우지 못한 호출도 서버에 도착한다.** 플러그인이 빠졌거나 hook 이 값을 돌려주지 않으면 원래 인자 그대로 간다. 그래서 서버는 profile 에 묶인 토큰으로 온 호출 가운데 서명이 없거나 틀린 것을 거절한다. `memory_read`, `artifact_write`, `agent_*` 가 모두 그렇다([ADR-032](../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)).

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
profile 이 빈 옛 토큰은 설정이 허용할 때만 `_fos_ctx` 를 보지 않고 전처럼 돈다. `agent_*` 는 설정과 무관하게 옛 토큰을 거절한다.
플러그인에 요구하는 동작이다. 서명할 수 없으면 `memory_read`, `artifact_write`, `agent_*` 를 모두 Hermes 쪽에서 막는다. 플러그인은 비공개 저장소 `fos-home-infra` 가 갖고, 이 저장소는 그 동작을 확인하지 못한다. 그래서 서버도 서명이 없는 호출을 묶인 토큰에서 거절한다. 플러그인이 막지 못해도 서버에서 같은 조건으로 막힌다.

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
| 인증 | `Authorization: Bearer <그 profile 의 MCP 토큰>`. `/mcp` 와 같은 토큰이다. profile 이 빈 옛 토큰은 거절한다 |
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
| `401` | 토큰이 없거나 모르는 토큰이거나 폐기됐다. profile 이 빈 옛 토큰이고 `assistant.mcp.legacy-user-tokens` 가 거짓이어도 같다. 사용자 JWT 로 불러도 같다. 본문이 없다 |
| `403` `{"code": "SESSION_BINDING_REJECTED"}` | profile 이 빈 옛 토큰(설정이 참일 때), JSON 이 아니거나 모양이 틀린 본문, 서명, 부모를 풀지 못함, session 값이 128자를 넘음, `child_session_id` 가 부모나 뿌리나 실행 줄이나 대화의 session 과 같음. 이유는 서버 로그에만 남는다 |
| `409` `{"code": "SESSION_BINDING_CONFLICT"}` | 그 `child_session_id` 가 다른 origin 으로 이미 등록돼 있다. 덮어쓰지 않는다 |

플러그인은 2xx 가 아니거나 연결하지 못하면 로그만 남기고 hook 을 돌려준다. 등록이 없는 하위 에이전트의 호출은 서버가 거절하므로 안전한 쪽으로 실패한다.
플러그인과 서버 모두 토큰, `sig`, 본문을 로그에 남기지 않는다.

#### profile 에 묶인 토큰에서만 동작한다

등록과 origin 판정, 아래 「취소가 아래로 내려가지 않는다」 의 취소 차단은 **profile 에 묶인 MCP 토큰**에서만 동작한다.
`agent_token.profile_name` 이 있고 `agent_token.user_id` 가 빈 토큰이다.

profile 이 빈 옛 토큰은 이렇게 된다.

- 등록 경로가 거절한다. 설정이 거짓이면 `401`, 참이면 `403` 이다
- `/mcp` 호출은 `_fos_ctx` 를 보지 않고 토큰의 사용자로 돈다. origin 실행을 찾지 않으므로 부모 turn 을 중지해도 하위 에이전트의 호출이 막히지 않는다
- `agent_*` 도구는 origin 실행이 없어 거절한다. `_fos_ctx` 를 붙여 와도 같다

그래서 하위 에이전트를 쓰는 profile 은 운영에서 모두 묶인 토큰이어야 한다. 어느 profile 의 토큰이 묶였는지 확인하는 방법은 `fos-home-infra` 가 갖는다.

옛 토큰 경로는 이 순서로 지운다. 앞 단계가 끝나야 다음 단계로 간다.

1. 모든 토큰을 profile 에 묶는다. `PUT /api/v1/admin/agent-tokens/{id}/profile` 이 묶으면서 `user_id` 를 비운다
2. `assistant.mcp.legacy-user-tokens` 를 거짓으로 돌린다
3. 옛 경로가 쓰이지 않는지 본다. 옛 토큰이 쓰이거나 거절될 때 남는 서버 경고 로그(`profile 이 묶이지 않은 옛 MCP 토큰`)가 0 건이어야 한다
4. `agent_token.user_id` 칸을 지운다
5. `McpPrincipal.legacyUserId` 와 설정을 지운다

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

### 제한 시간은 우리가 정하지만 상한은 있다

| 항목 | 값 |
| --- | --- |
| 호출 하나의 제한 시간 | 기본 300초 |
| 서버마다 바꾸는 자리 | 그 서버 설정의 `timeout` |
| 전역으로 바꾸는 자리 | `timeouts.mcp.tool_call` |
| 연결 제한 시간 | 기본 60초 |

제한 시간을 20초로 줄이고 30초 걸리는 도구를 부르자 아래가 도구 결과로 돌아왔고
실행 자체는 정상 종료했다.

```text
MCP call failed: TimeoutError: MCP call timed out after 20.0s (configured timeout: 20.0s)
```

**실행을 기다리는 도구는 이 시간 안에 끝나야 한다.**
오래 걸리는 작업을 그 안에서 기다리면 제한 시간에 걸린다.
아래 「기다리는 도구는 실제로 끊긴다」 가 그 실측이다.

### 재귀를 막는 자리가 Hermes 에 없다

도구 안에서 다시 Runs API 를 부르게 하고 깊이를 하나씩 올렸다.
깊이 2, 3, 4 가 모두 실행됐고 5가 시작되려 할 때 **측정용 서버가 스스로 끊었다.**
Hermes 는 한 번도 개입하지 않았다.

Hermes 는 그 도구가 자기를 다시 부른다는 것을 알지 못한다.
도구 호출은 그저 외부 HTTP 호출이다.
**멈추는 자리를 Control Plane 이 가져야 한다.** 실행 나무의 깊이를 우리가 세고 우리가 거절한다.

### 기다리는 도구는 실제로 끊긴다

위 재귀 측정에서 제한 시간을 120초로 두었는데, 뿌리 실행의 도구 호출이 그 시간에 걸렸다.
아래가 뿌리 실행의 대화에 남은 도구 결과다.

```text
{"error": "MCP call failed: TimeoutError: MCP call timed out after 120.0s ..."}
```

**끊긴 뒤에도 그 아래 실행들은 계속 돌았다.** 깊이 2와 3과 4가 모두 완료로 끝났다.
도구 호출이 끊기는 것과 그 도구가 시작한 실행이 멈추는 것은 별개다.

뿌리 실행은 그 뒤 `Service temporarily overloaded` 로 실패했다.
겹쳐 도는 실행이 쌓여 한 gateway 와 provider 에 몰린 결과다.
`gateway.api_server.max_concurrent_runs` 의 기본값이 10 이고 넘으면 429 를 준다.

그래서 이 구조를 쓴다면 도구가 실행이 끝날 때까지 기다리게 만들지 않는다.
실행 번호를 바로 돌려주고 진행은 따로 묻는 형태여야 한다.

### 취소가 아래로 내려가지 않는다

`POST /v1/runs/{run_id}/stop` 은 동작한다.
`{"status": "stopping"}` 을 주고 잠시 뒤 조회하면 `cancelled` 다.

**그러나 그 실행의 도구가 시작한 실행은 멈추지 않는다.**
뿌리 실행을 취소한 뒤에도 그 도구가 만든 아래 실행이 완료로 끝나고
다시 그 아래를 시작하는 것을 실측했다.
취소를 아래로 전파하는 것도 Control Plane 의 몫이다.

모델이 부른 `delegate_task` 자식도 같다. 위 「하위 에이전트는 부모 run 보다 오래 산다」 대로 background 자식은 부모 run 에서 떨어져 나가 따로 돈다.
부모 run 을 멈춰도 그 자식은 계속 돌 수 있다고 본다. 중지한 뒤 자식이 실제로 계속 도는지는 실행으로 확인하지 않았다.

그래서 Control Plane 은 지금 이만큼 막는다.

| 무엇 | 지금 |
| --- | --- |
| 자식의 Control Plane MCP 호출(`memory_read`, `artifact_write`, 앞으로의 `agent_*`) | origin 실행이나 그 뿌리 실행이 `CANCELLED` 면 거절한다([ADR-037](../adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)). 다른 거절과 같은 도구 결과다 |
| 자식의 Hermes 자체 도구(웹 검색, 터미널 등) | 막지 못한다 |
| 자식 run 자체 | 멈추지 못한다. `subagent_stop` hook 과 Hermes 의 비동기 위임 제어가 자식을 멈출 수 있는지 조사한 뒤 turn 중지에 잇는다 |

### 취소한 실행의 조회 응답

2026-09-25 에 v0.21.0 에서 실제 중지를 한 번 왕복시켜 확인했다.
v0.21.5 의 중단·미완료 응답 차이는 [「Runs 응답과 사건의 버전 차이」](upgrades.md#runs-응답과-사건의-버전-차이) 에 있다.

| 무엇 | 실측 |
| --- | --- |
| `GET /v1/runs/{run_id}` 의 `status` | `cancelled` |
| 그 응답의 `output` | 비어 있다 |
| 그 응답의 `usage` | 비어 있다. 그래서 중지한 실행의 토큰 기록도 빈다 |
| 그 응답의 `session_id` | 그 대화의 session 이다. 중지해도 바뀌지 않는다 |
| 사건 스트림 | 중지를 보낸 뒤 10초 안에 닫혔다 |

**멈춘 자리까지의 답은 조회 응답에서 얻을 수 없다.** 스트림으로 받은 조각을 모아 둔 것만이 그 답이다.
[ADR-021](../adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) 이 뒤받침으로 둔 경로가 실제로 쓰이는 경로다.

**API server 의 session 은 그 대화 첫 실행의 `run_id` 와 같다.** 뒤의 turn 은 같은 값을 이어 쓴다.
중지한 turn 뒤에도 그 값이 그대로여서, 다음 turn 이 중지 전의 맥락을 기억했다.
첫 turn 을 중지한 대화는 그 중지한 실행의 `run_id` 가 곧 session 이다.
이 세 문장은 Hermes 가 session 을 정하는 대화의 측정이다. 이제 새 대화는 Control Plane 이 첫 turn 전에 정한 `fos-<uuid>` 가 session 이고, 첫 turn 을 중지해도 그 값이 그대로다([ADR-031](../adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md)).

중지한 실행은 그 session 으로 실제로 돈 모델을 읽지 못했다. Control Plane 은 그때 요청에 보낸 모델을 적는다.

### API server 에서 위임 도구가 빠지는 원인

2026-09-28 에 Hermes v0.21.0 의 소스로 원인을 확인했다.
API 경로의 도구 목록을 계산하는 `_get_platform_tools` 는 마지막에 `agent.disabled_toolsets` 를 뺀다.
그래서 도구 설정에 `delegation` 이 있어도 비활성화 목록에 포함되면 위임 도구가 실행에 노출되지 않는다.
같은 날 실제 실행에서 `subagent.start` 가 오는 것을 확인했지만,
최상위 위임은 부모가 먼저 끝나 부모 스트림으로 `subagent.complete` 를 받지 못했다.

### 긴 결과는 잘리지 않고 파일로 빠진다

20만 자를 돌려주는 도구를 불렀다.
전체가 spillover 파일로 저장되고 대화에는 2천 자 남짓만 들어갔다.

```text
This tool result was too large (200,014 characters, 195.3 KB).
Full output saved to: <spillover 파일>
Use the read_file tool with offset and limit to access specific ...
```

| 구간 | 무슨 일이 일어나나 |
| --- | --- |
| 약 5만 자 이하 | 그대로 대화에 들어간다 |
| 약 5만 자부터 200만 자까지 | 전체를 파일로 저장하고 대화에는 미리보기만 넣는다 |
| 200만 자 초과 | 앞 40%와 뒤 60%만 남기고 잘라 낸다 |

**미리보기만 본 모델이 본문을 읽으려면 `read_file` 을 쓸 수 있어야 한다.**
파일 도구를 잠근 에이전트는 그 본문에 닿지 못한다.

MCP 결과는 `<untrusted_tool_result>` 로 감싸여 들어간다.
그 안의 내용을 지시가 아니라 데이터로 다루라는 안내가 함께 붙는다.
