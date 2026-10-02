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

**종료된 자식 session 은 다시 조회해도 값이 같다.**
2026-10-01 에 종료된 자식 session 5개를 각각 두 번 조회해 토큰과 종료 시각이 모두 같음을 확인했다.
본문과 답은 읽거나 기록하지 않았다.
이 검사는 이미 종료된 session 의 반복 조회를 확인한 것이며 실행 중 값의 변화까지 확인한 것은 아니다.

근거는 [v0.21.3 session 직렬화](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py) 의
`_session_response`, `_message_response`, `_handle_list_sessions` 와
`agent/conversation_loop.py`, `agent/turn_finalizer.py` 의 토큰 누적이다.

### 자식 session 의 provider 는 저장소에만 있다

2026-10-02 에 v0.21.5(`v2026.9.24`)의 소스를 읽고, 운영 Hermes 에서 읽기 전용으로 집계해 확인했다.

| 확인한 것 | 근거 |
| --- | --- |
| API server 의 session 응답은 정해 둔 칸만 내보내고 그 목록에 provider 가 없다. 단건 조회와 목록이 같은 함수를 쓴다 | [`gateway/platforms/api_server.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server.py) 의 `_session_response` 의 `safe_keys` |
| session 저장소의 `sessions` 표에 `billing_provider` 칸이 있다. profile 마다 저장소가 따로 있다 | [`hermes_state_common.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_state_common.py) 의 `SCHEMA_SQL` |
| session 을 만들 때 요청한 경로가 먼저 적힌다. 그 경로가 실패하고 fallback 이 성공하면, 처음으로 사용량이 잡힌 호출의 모델과 provider 로 그 줄을 고친다. 그 뒤에는 줄을 바꾸지 않는다 | [`hermes_state_usage.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_state_usage.py) 의 `update_token_counts` 의 `first_accounted_route` |
| 호출마다의 모델과 provider 는 `session_model_usage` 표에 짝으로 쌓인다. 본 대화의 호출은 `task` 가 빈 줄이고, 보조 호출은 `task` 에 이름이 있다 | 같은 파일의 `_record_model_usage` 와 [`hermes_state_sessions.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_state_sessions.py) 의 `get_recent_session_model_route` |
| `subagent_stop` hook 은 부모와 자식의 session 번호, 역할, 요약, 상태, 도구 이력, 소요 시간을 받는다. provider 는 받지 않는다 | [`tools/delegate_tool_results.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_results.py) 의 `_fire_subagent_stop_hooks` |

**`sessions` 한 줄은 모델과 provider 의 짝을 하나만 담는다.**
자식이 도는 중에 모델이나 provider 가 바뀌면 그 줄의 값은 처음 것이고, 바뀐 뒤의 호출은 `session_model_usage` 에만 남는다.
그래서 한 줄의 값으로 금액을 환산하려면 `session_model_usage` 에서 `task` 가 빈 줄의 짝이 하나뿐인지 함께 본다.

운영 집계의 결과다. 값의 개수만 세었고 본문은 읽지 않았다.

| 항목 | 값 |
| --- | --- |
| `source` 가 `subagent` 인 줄 | 115 |
| 그 가운데 `billing_provider` 가 채워진 줄 | 115 |
| 호출이 있었고 끝났는데 `billing_provider` 가 빈 줄 | 0 |
| `task` 가 빈 `session_model_usage` 의 짝이 하나인 줄 | 115 |
| `sessions` 의 provider 와 `session_model_usage` 의 provider 가 다른 줄 | 0 |

대시보드도 이 저장소를 읽는다. 대시보드는 gateway 와 다른 프로세스이고 profile 마다 저장소 파일을 따로 연다.
근거는 [`hermes_cli/web_server_sessions.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/web_server_sessions.py) 의 `_open_session_db_for_profile` 이다.
이 함수의 읽기 전용 열기는 저장소가 비었거나 스키마가 낡았으면 쓰기 연결을 한 번 열어 고친다.

Control Plane 은 이 값을 대시보드 plugin 의 읽기 경로로 받는다([ADR-067](../adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md)).
plugin 은 Hermes 의 저장소 클래스를 쓰지 않고 SQLite 의 읽기 전용 방식으로 파일을 직접 연다.
**Hermes 판을 올릴 때 위 표의 표 이름과 칸 이름을 다시 확인한다.**

### 자식의 모델은 부모의 것이 아니다

부모를 `openai/gpt-oss-20b` 로 돌린 실행에서 자식이 `z-ai/glm-5.2` 로 돌았다.
자식 모델은 `delegation.provider` 와 `delegation.model` 이 정하고,
비어 있으면 부모가 아니라 별도 기본값으로 떨어진다.
실행별 비용을 보려면 이 값을 명시해야 한다.

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
**멈추는 자리를 Control Plane 이 가져야 한다.** 실행 트리의 깊이를 우리가 세고 우리가 거절한다.

### 기다리는 도구는 실제로 끊긴다

위 재귀 측정에서 제한 시간을 120초로 두었는데, 루트 실행의 도구 호출이 그 시간에 걸렸다.
아래가 루트 실행의 대화에 남은 도구 결과다.

```text
{"error": "MCP call failed: TimeoutError: MCP call timed out after 120.0s ..."}
```

**끊긴 뒤에도 그 아래 실행들은 계속 돌았다.** 깊이 2와 3과 4가 모두 완료로 끝났다.
도구 호출이 끊기는 것과 그 도구가 시작한 실행이 멈추는 것은 별개다.

루트 실행은 그 뒤 `Service temporarily overloaded` 로 실패했다.
겹쳐 도는 실행이 쌓여 한 gateway 와 provider 에 몰린 결과다.
`gateway.api_server.max_concurrent_runs` 의 기본값이 10 이고 넘으면 429 를 준다.

그래서 이 구조를 쓴다면 도구가 실행이 끝날 때까지 기다리게 만들지 않는다.
실행 번호를 바로 돌려주고 진행은 따로 묻는 형태여야 한다.

### 취소가 아래로 내려가지 않는다

`POST /v1/runs/{run_id}/stop` 은 동작한다.
`{"status": "stopping"}` 을 주고 잠시 뒤 조회하면 `cancelled` 다.

**그러나 그 실행의 도구가 시작한 실행은 멈추지 않는다.**
루트 실행을 취소한 뒤에도 그 도구가 만든 아래 실행이 완료로 끝나고
다시 그 아래를 시작하는 것을 실측했다.
취소를 아래로 전파하는 것도 Control Plane 의 몫이다.

모델이 부른 `delegate_task` 자식도 같다. [`fos-ctx.md`](fos-ctx.md#하위-에이전트는-부모-run-보다-오래-산다) 의 「하위 에이전트는 부모 run 보다 오래 산다」 대로 background 자식은 부모 run 에서 떨어져 나가 따로 돈다.
중지한 뒤 자식이 실제로 계속 도는지는 실행으로 확인하지 않았다. 아래는 소스로 확인한 것이다.

#### native 하위 에이전트를 멈추는 길

2026-09-30 에 v0.21.5(`v2026.9.24`)의 소스를 읽어 확인했다.

| 확인한 것 | 근거 |
| --- | --- |
| `POST /v1/runs/{run_id}/stop` 은 부모 agent 에 hard interrupt 를 걸고, 그 interrupt 는 부모에 붙은 자식에게만 내려간다. 동기 위임 자식(오케스트레이터 자식이 부른 깊이 1 이상의 위임)은 붙어 있어 함께 멈춘다 | [`agent/interrupt_control.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/interrupt_control.py) 의 `interrupt`, [`tools/delegate_tool_child_run.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_child_run.py) 의 `_attach_child` |
| background 자식은 dispatch 직전에 부모에서 떼어지고 부모의 interrupt 를 따르지 않게 돈다. 그래서 부모 run 의 중지가 닿지 않는다 | [`tools/delegate_tool_dispatch.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_dispatch.py) 의 `_dispatch_background`(`_detach_child`, `honor_parent_interrupt=False`) |
| API server 에 background 자식을 멈추는 HTTP 경로가 없다. run 경로는 `events`, `approval`, `steer`, `stop` 뿐이다. 대시보드에도 없다 | [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `_http_routes` |
| `subagent_stop` hook 은 자식이 끝난 뒤 오는 알림이다. 반환값은 버려져 자식을 멈추는 수단이 아니다 | [`tools/delegate_tool_results.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_results.py) 의 `_fire_subagent_stop_hooks` |
| `pre_tool_call` hook 이 `{"action": "block"}` 을 돌려주면 도구 호출 한 번을 거절한다. 자식 실행을 끝내지는 않는다 | [`hermes_cli/plugins.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py) 의 `_get_pre_tool_call_directive_details` |
| background 자식을 멈추는 함수는 `tools.async_delegation.interrupt_for_session(parent_session_id=...)` 이다. 대화형 gateway 의 `/stop` 이 이것을 부르고, API server 는 부르지 않는다. 공개 plugin API 가 아니다 | [`tools/async_delegation.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/async_delegation.py) |
| turn 이 끝나면 `on_session_end` hook 이 `session_id`, `interrupted`, `turn_exit_reason` 을 받는다. `/v1/runs/{run_id}/stop` 으로 멈춘 turn 은 `interrupted_by_user` 다 | [`agent/turn_finalizer.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/turn_finalizer.py), [`agent/turn_iteration_prep.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/turn_iteration_prep.py) |

**그래서 core 를 고치지 않고 멈출 수 있는 길은 하나이고 조건이 붙는다.**
profile 플러그인이 `on_session_end` 에서 `interrupted` 이고 `turn_exit_reason` 이 `interrupted_by_user` 일 때 `interrupt_for_session(parent_session_id=session_id)` 를 부르는 것이다.
gateway 의 `/stop` 과 같은 함수이고, 부모 session 이 정확히 같은 자식만 멈춘다. Control Plane 이 대화마다 고유한 session 을 보내므로 다른 사용자의 자식을 멈추는 길은 없다.

이 길의 한계다.

- **부모 run 이 이미 끝났으면 멈추지 못한다.** 중지가 아무것도 하지 않고 hook 도 다시 오지 않는다. background 자식은 대개 이 경우다
- 공개 API 가 아닌 내부 함수에 기댄다. Hermes 버전을 올릴 때마다 함수 이름과 인자를 다시 확인해야 한다
- 압축으로 turn 중간에 session 이 바뀌면 값이 맞지 않아 자식을 놓친다. 엉뚱한 자식을 멈추지는 않는다
- 한 프로세스가 여러 profile 을 multiplex 하면 기록이 공유된다. 격리는 session 이 고유한 것에만 기댄다

**이 저장소는 이 길을 구현하지 않는다.** 플러그인은 이 저장소의 `hermes/plugins/fos-ctx/` 가 갖고, 이 길은 그 플러그인의 후속 작업 후보다.
그 전까지 Control Plane 이 막는 것은 아래 표와 같다. 멈춘 turn 의 자식이 사용자의 권한을 쓰는 길은 이미 막혀 있다([ADR-037](../adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).

그래서 Control Plane 은 지금 이만큼 막는다.

| 무엇 | 지금 |
| --- | --- |
| 자식의 Control Plane MCP 호출(`memory_read`, `artifact_write`, `agent_list`, `agent_delegate`, `agent_status`, `agent_stop`) | origin 실행이나 그 루트 실행이 `CANCELLED` 면 거절한다([ADR-037](../adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)). 다른 거절과 같은 도구 결과다 |
| 자식의 Hermes 자체 도구(웹 검색, 터미널 등) | 막지 못한다 |
| 자식 run 자체 | 멈추지 못한다. 동기 위임 자식만 부모 run 의 중지와 함께 멈춘다. background 자식을 멈추는 길은 위 「native 하위 에이전트를 멈추는 길」 에 있고 구현하지 않았다 |

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
이 세 문장은 Hermes 가 session 을 정하는 대화의 측정이다. 이제 새 대화는 Control Plane 이 첫 turn 전에 정한 `fos-<uuid>` 가 session 이고, 첫 turn 을 중지해도 그 값이 그대로다([ADR-031](../adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-루트-session-으로-잇는다.md)).

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
