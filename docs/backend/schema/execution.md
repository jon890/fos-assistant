# 실행

에이전트 실행 한 번의 기록과 그 실행에 딸린 사건, 자식 사용량 재조회 작업, 스킬 호출, 하위 에이전트 session 등록을 저장하는 표의 칸과 제약을 갖는다.

## agent_execution

에이전트가 한 번 답한 기록이다.
대부분 대화에 속해 대화 하나에 여러 줄이 달리고, 한 줄이 곧 메시지 하나다.
추천 질문을 만드는 실행은 대화 없이 한 줄만 생긴다.

**이 줄은 실행이 끝난 뒤가 아니라 시작할 때 만들어진다.**
근거는 [ADR-011](../../adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 누가 물었는가 |
| `conversation_id` | BIGINT NULL | 비어 있으면 대화 밖에서 돈 실행이다. 지금은 추천 질문을 만드는 실행뿐이다 |
| `agent_id` | BIGINT | 어느 에이전트의 실행이었는가 |
| `parent_execution_id` | BIGINT NULL | 이 실행을 부른 실행. 사용자가 부른 것이면 비어 있다 |
| `root_execution_id` | BIGINT NULL | 이 실행이 속한 트리의 루트. 루트 자신은 비어 있다 |
| `retry_of_execution_id` | BIGINT NULL | 같은 turn 을 다른 모델로 다시 시도한 실행이 가리키는 직전 실행. 지금은 채우는 경로가 없어 새 실행은 늘 비어 있다 |
| `profile_name` | VARCHAR(64) | |
| `hermes_run_id` | VARCHAR(128) NULL | 실행을 제출한 직후에 적는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 이 실행이 속한 Hermes session. 대화 turn 은 그 대화의 루트 session 이고, 루트가 없는 옛 대화는 보낸 session 이다. 압축 교체 뒤에는 보낸 session 과 다를 수 있다. 흐름의 하위 실행과 위임한 자식은 Control Plane 이 정한 `fos-<uuid>` 다. 제출하기 전에 적는다. 최상위 session 의 MCP 호출과 최상위 자식의 등록이 서명한 루트 session 과 `profile_name` 으로 도는 실행을 찾을 때 쓴다([ADR-032](../../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)). 하위 에이전트 session 은 이 칸이 아니라 `hermes_session_binding` 으로 찾는다. 이 칸이 생기기 전의 실행과 Memory 제안, 추천 질문을 만드는 실행은 비어 있다 |
| `delegation_key` | VARCHAR(64) NULL, 유일 | `agent_delegate` 로 만든 실행만 채운다. `v1`, 부모 실행의 `profile_name`, 루트 session, 그 호출의 session, `tool_call_id` 를 줄바꿈으로 이은 글의 SHA-256 소문자 16진수다. 같은 호출이 다시 와도 실행을 하나만 만든다. `agent_status` 와 `agent_stop` 은 이 칸이 있는 실행만 답한다. 정의는 [ADR-032](../../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다 |
| `output_text` | MEDIUMTEXT NULL | `agent_delegate` 로 만든 실행이 끝났을 때의 답. `agent_status` 와 `agent_stop` 이 `SUCCEEDED` 와 `CANCELLED` 에서 돌려준다. 끝난 상태와 같은 저장에서 적는다. `assistant.delegation.output-max-chars`(기본 100,000자)를 넘으면 자르고 잘렸다는 한 줄을 붙인다. 다른 실행은 채우지 않는다(대화 답은 `chat_message` 가 갖는다) |
| `result_delivered_at` | DATETIME(6) NULL | 위임 실행의 끝난 결과를 부모에게 전한 시각. 부모가 `agent_status` 나 `agent_stop` 으로 끝난 상태를 받았거나, Control Plane 이 부모 대화를 깨운 turn 에 넣었을 때 적는다. `agent_delegate` 가 줄을 만든 뒤 제출 전에 끝나 `SUBMIT_FAILED` 를 돌려줄 때도 적는다. 부모가 번호를 모르는 결과를 다시 전하지 않기 위해서다. 이 칸이 생기기 전에 끝난 위임 실행은 마이그레이션이 `finished_at`(없으면 그때 시각)으로 채워 깨우지 않는다. 그때 `RUNNING` 이던 줄은 비워 두며, 기동 정리가 끝난 상태로 적은 뒤 전한다([`turn-control.md`](../turn-control.md) 의 「기동할 때 남은 실행 정리」). 비어 있고 `SUCCEEDED` 나 `FAILED` 인 위임 실행이 깨울 대상이다([ADR-040](../../adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)). 먼저 살펴보기가 끝나면 그 트리의 위임 줄에 도는 중이어도 적는다([`proactive-check.md`](../proactive-check.md) 의 「끝날 때」). 저장소의 조건부 update 로만 채우고 엔티티 저장에서는 빠진다(`updatable = false`) |
| `provider`, `model` | VARCHAR | 실제로 돈 provider 와 모델. Hermes 의 session 이 답한 값이고, 읽지 못하면 요청한 값이다. 기본값으로 보냈고 둘 다 읽지 못하면 비어 있다 |
| `reasoning_effort` | VARCHAR(16) NULL | 이 실행에 요청한 effort. 기본값으로 보냈으면 비어 있다. 이 칸이 생기기 전의 실행도 비어 있다 |
| `reasoning_defaults_checked_at` | DATETIME(6) NULL | `reasoning_effort`가 비어 있고 profile 기본값을 정상 응답으로 읽어 확인한 시각. 응답에 effort가 없어도 적어 같은 실행을 다시 조회하지 않는다. 조회 실패면 비워 다시 시도한다 |
| `reasoning_effort_source` | VARCHAR(20) NULL | `reasoning_effort` 의 출처. `REQUESTED`, `AGENT_DEFAULT`, `PROFILE_DEFAULT`, `UNKNOWN` 이다. 이 칸이 생기기 전의 실행은 비어 있고 추정해 채우지 않는다 |
| `model_tier` | VARCHAR(16) NULL | 실행을 시작할 때 해석한 모델 단계. 단계 없이 돈 실행은 비어 있다 |
| `cost_mode` | VARCHAR(20) | 실행 당시 에이전트의 `SUBSCRIPTION` 또는 `API` |
| `event_observation` | VARCHAR(20) | 사건 관측 범위. 과거 실행과 제출 전 실행은 `UNKNOWN`, 수집 중은 `OBSERVING`, 정상 종료는 `OBSERVED`, 비수집이나 읽기·저장 실패는 `INCOMPLETE`다. 실행 성공과 자식 비용 확정 여부와는 별개다 |
| `status` | VARCHAR(20) | `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `error_code` | VARCHAR(64) NULL | |
| `input_tokens`, `cached_input_tokens`, `output_tokens`, `total_tokens` | BIGINT NULL | provider 가 알려준 것만 채운다 |
| `context_chars` | BIGINT NULL | 이 실행의 공통 답변 지침과 Memory 문맥의 글자 수. Memory가 없어도 공통 지침은 센다. 뒤에 붙는 묻는 형식 안내와 다시 생성 지시는 세지 않는다 |
| `context_omitted_items` | INT NULL | 자리가 없어 이 실행의 문맥에서 빠진 Memory 항목 수. 이 칸이 생기기 전의 실행과 문맥을 조립하지 않은 실행은 비어 있다 |
| `runtime_fingerprint` | VARCHAR(64) NULL | 실행 당시 Hermes 의 고정 프롬프트 구성을 가리키는 지문. 그 값을 주는 HTTP 경로가 아직 없어 지금은 항상 비어 있고, 그동안 사용량 화면의 지문 축은 빈 목록을 돌려준다 |
| `instructions_hash` | VARCHAR(64) NULL | 공통 답변 지침과 Memory 문맥의 SHA-256 앞 16바이트를 16진수로 적은 값. 본문은 개인 Memory를 담을 수 있어 저장하지 않는다. 뒤에 붙는 묻는 형식 안내와 다시 생성 지시는 제외한다. Memory가 없어도 공통 지침의 지문을 기록한다 |
| `latency_ms` | BIGINT NULL | 끝나지 않은 실행은 비어 있다 |
| `estimated_cost_micros` | BIGINT NULL | 공개 API 가격으로 환산한 금액. 통화 단위의 100만분의 1 |
| `actual_cost_micros` | BIGINT NULL | 실제로 청구되는 금액. 구독 경로는 비어 있다 |
| `cost_currency` | CHAR(3) NULL | |
| `pricing_version` | VARCHAR(32) NULL | 이 금액을 계산한 가격표 |
| `started_at` | DATETIME(6) | |
| `finished_at` | DATETIME(6) NULL | 끝나지 않은 실행은 비어 있다 |
| `request_received_at`, `submitted_at`, `first_delta_at` | DATETIME(6) NULL | `finished_at` 과 함께 실행 구간을 표시한다. 첫 assistant delta 의 본문은 저장하지 않는다 |

토큰 수는 실행 한 번의 **합계**다.
실행 안에서 LLM 호출이 여러 번 일어나고 그 내역은 오지 않는다.

자식 실행의 토큰은 부모의 합계에 들어 있지 않다.
근거는 [`hermes/delegation.md`](../../hermes/delegation.md) 의 「자식 session 으로 결과와 토큰을 보완한다」 절에 있다.
그래서 부모와 자식을 더한 합계는 실행 트리의 줄을 더해서 만든다.
실행 줄이 없는 native 자식은 `subagent_usage_job` 줄을 더한다.
어느 줄도 두 번 세지 않는다.

`CANCELLED` 는 중지한 turn 과 `agent_stop` 으로 멈춘 위임 실행에 쓴다.

`hermes_session_id` 와 `status` 에 함께 색인을 둔다. 최상위 session 의 MCP 호출과 최상위 자식의 등록마다 서명한 루트 session 으로 도는 실행을 찾기 때문이다.
그 session 을 가진 도는 실행이 둘 이상이면 어느 쪽도 부모로 쓰지 않고 거절한다. 대화 하나에는 도는 turn 이 하나뿐이라 보통 생기지 않는다.

금액을 0 으로 채우지 않는다.
0 은 공짜라는 뜻으로 읽히기 때문이다.
환산액과 실제 청구액을 나눈 근거는
[ADR-014](../../adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md)에 있다.

### 사건 관측 범위

새 실행은 Hermes에 제출할 때 `INCOMPLETE`로 적는다. 대화의 사건 수집 경로는 제출 직후 `OBSERVING`으로 바꾸며, 종료 사건을 받고 스트림이 정상적으로 닫히면 `OBSERVED`로 적는다. 종료 사건 없는 조기 EOF, 읽기·사건 저장 실패, 대기 상한이나 중지 유예 종료는 `INCOMPLETE`로 남긴다. 실패 표시를 뒤늦은 스트림 종료로 지우지 않는다. 기동 복구는 `OBSERVING`으로 남은 줄을 `INCOMPLETE`로 바꾸고 답과 확인한 사용량을 보존한다. 사건을 수집하지 않는 Flow 단계와 FOS 위임 등은 `INCOMPLETE`로 남는다. Hermes에 제출하지 않는 합성 루트는 `UNKNOWN`이다.

월 합계의 `observationIncompleteExecutions`는 같은 사용자와 시작 시각 구간의 끝난 `INCOMPLETE` 실행만 센다. 관리자에게만 보내며, 자식 건수나 비용에 더하지 않는다. 마이그레이션 이전의 `UNKNOWN` 실행은 경고 수에서 제외한다. 관측 여부를 추정하지 않고, 과거 실행이 배포 직후 경고 수를 채우지 않게 하기 위해서다. 화면은 과거 실행의 관측 범위를 확인하지 않았다는 안내를 유지한다.

### 끝나지 않은 실행

`RUNNING` 인 줄은 사용량 목록에는 「도는 중」으로 보인다.
끝나지 않아 소요 시간과 금액은 비워 둔다.
월 비용 합계에서는 뺀다.
빼지 않으면 「가격을 찾지 못한 실행」 으로 세어져,
아직 안 끝난 것과 가격을 모르는 것이 한 숫자에 섞인다.

기동할 때 `RUNNING` 으로 남아 있는 줄은 Hermes 에 물어 정한다.
절차는 [`turn-control.md`](../turn-control.md) 의 「기동할 때 남은 실행 정리」 가 갖는다.
그 경로가 실패로 적을 때 쓰는 `error_code` 는 넷이다.

| `error_code` | 뜻 |
| --- | --- |
| `ORPHANED` | run 번호가 없어 묻지 못했다. 흐름 turn 의 루트도 이 값이다 |
| `REMOTE_RUN_LOST` | Hermes 가 그 run 을 모른다(404) |
| `RECONCILE_TIMEOUT` | 상한까지 끝나지 않아 중지를 보냈다 |
| `RECONCILE_UNREACHABLE` | 상한까지 Hermes 에 한 번도 닿지 못했다 |

넷 모두 사용량 목록에서 「중간에 중단됨」 으로 보인다.

## execution_event

실행 하나가 도는 동안 일어난 일을 우리 이름으로 옮겨 적은 것이다.
Hermes 가 보낸 원래 payload 를 통째로 넣지 않는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `execution_id` | BIGINT | 어느 실행의 사건인가 |
| `sequence` | INT | 그 실행 안에서의 순서. 1부터 센다 |
| `event_type` | VARCHAR(40) | 아래 표의 값 중 하나 |
| `tool_name` | VARCHAR(128) NULL | 도구 사건일 때 채운다. 붙은 커넥터 서버의 도구는 Hermes 등록 이름 `mcp__<서버>__<도구>` 다 |
| `subagent_name` | VARCHAR(128) NULL | 하위 에이전트 사건일 때 채운다 |
| `hermes_session_id` | VARCHAR(128) NULL | 하위 에이전트가 따로 session 을 가지면 적는다 |
| `duration_ms` | BIGINT NULL | 끝난 사건에만 있다 |
| `failed` | BOOLEAN NULL | 완료 사건의 실패 여부. Hermes 가 알려주지 않으면 비운다 |
| `detail` | VARCHAR(500) NULL | 하위 에이전트 사건이면 그 목표. 도구 사건은 ADR-047에 따라 비밀값과 UUID를 가린 뒤 저장하고, 응답에는 ADR-038이 정한 사람에게만 싣는다. 옛 커넥터 에이전트의 도구 내용과, 다른 에이전트에 붙은 커넥터 서버의 도구 내용은 전체를 가린다 |
| `model` | VARCHAR(128) NULL | 하위 에이전트가 돈 모델. 하위 에이전트 사건에만 있다 |
| `input_tokens`, `output_tokens` | BIGINT NULL | 하위 에이전트가 쓴 토큰. `SUBAGENT_COMPLETED` 에만 있다 |
| `occurred_at` | DATETIME(6) | |

`execution_id` 와 `sequence` 를 함께 유일하게 둔다.

**`subagent_name`은 이름, `subagent_id`, `goal` 순서로 채운다.**
이름이 없는 사건의 `preview` 에서 이름처럼 보이는 글자를 뽑아 채우지 않는다.
그것이 실제 이름인지 우리가 만든 것인지 구분할 수 없기 때문이다.

**`hermes_session_id`, `model`, 토큰은 SSE 또는 종료된 자식 session 조회에서 확인한 값이다.**
[`hermes/delegation.md`](../../hermes/delegation.md) 의 「자식 토큰을 SSE 로 받을 수 있다」 절이
`subagent.start` 와 `subagent.complete` 에 오는 칸을 적는다.
`child_session_id` 를 `hermes_session_id` 에, `goal` 을 `detail` 에 옮긴다.
싣지 않는 버전에서는 이 칸들이 비고 `detail` 에 `preview` 가 들어간다.

부모가 끝난 뒤에도 완료 사건이 없으면 [모델 단계와 실행 기록](../../model-tiers.md)의 재조회로 보완한다.
`completed_child_session_id` VARCHAR(128) NULL은 자식 완료 사건의 중복 저장을 막는다.
`(execution_id, completed_child_session_id)`가 유일하며 다른 종류의 사건은 이 칸을 비운다.
기존 중복 완료 사건은 최신 한 줄만 키를 채우고 나머지 이력은 보존한다.

이 토큰은 화면이 하위 에이전트가 무엇을 썼는지 보이는 데만 쓴다.
사용량 합계에 더하지 않는다. native 자식의 합계는 아래 `subagent_usage_job` 줄의 값으로 낸다.

| `event_type` | 언제 |
| --- | --- |
| `RUN_STARTED` | 실행이 시작됐다 |
| `RUN_COMPLETED` | 실행이 끝났다 |
| `RUN_FAILED` | 실행이 실패했다 |
| `RUN_CANCELLED` | 사용자가 중지했다 |
| `TOOL_STARTED` | 도구를 부르기 시작했다 |
| `TOOL_COMPLETED` | 도구 호출이 끝났다 |
| `SUBAGENT_STARTED` | 하위 에이전트가 시작됐다 |
| `SUBAGENT_COMPLETED` | 하위 에이전트가 끝났다 |
| `PROVIDER_SWITCHED` | 옛 실행에만 남은 값이다. 앞 provider 가 막혀 다음 모델로 다시 시도했다는 뜻이고, 지금은 이 값을 적는 경로가 없다 |

우리가 모르는 사건은 저장하지 않고 버린다. 버렸다는 사실만 로그로 남긴다.
근거는 [ADR-013](../../adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md)에 있다.

## subagent_usage_job

native 자식 한 명의 사용량 원장 줄이자, 그 사용량을 session 에서 조회하는 작업이다.
부모 실행이 끝나면 session 이 있는 시작 사건마다 한 줄이 생긴다.
재조회 규칙과 합계에 더하는 규칙은 [모델 단계와 실행 기록](../../model-tiers.md) 의 「비동기 자식 사용량」 이 정한다.
근거는 [ADR-062](../../adr/ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md) 에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `execution_id` | BIGINT | 부모 실행 |
| `child_session_id` | VARCHAR(128) | 조회할 자식 session |
| `parent_session_id` | VARCHAR(128) NULL | 부모 실행의 session |
| `profile_name` | VARCHAR(64) | 부모 실행의 profile |
| `api_base_url` | VARCHAR(512) | 조회를 보낼 API 주소 |
| `status` | VARCHAR(16) | `WAITING`, `DONE`, `EXPIRED` |
| `unconfirmed_reason` | VARCHAR(32) NULL | 사용량이나 금액을 확인하지 못한 까닭. `EXPIRED` 는 `AGENT_MISSING`, `PROFILE_CHANGED`, `DEADLINE`, 금액 없는 `DONE` 은 `PROVIDER_UNKNOWN`, `USAGE_UNKNOWN`, `PRICE_UNKNOWN` |
| `created_at` | DATETIME(6) | |
| `next_attempt_at` | DATETIME(6) | 다음 조회 시각 |
| `expires_at` | DATETIME(6) | 조회 기한. 부모 실행이 끝난 뒤 24시간이다 |
| `attempts`, `backoff_attempts` | INT | 조회 횟수 |
| `provider` | VARCHAR(64) NULL | 자식이 돈 provider. session 응답이 주지 않으면 대시보드 plugin 에서 읽고([ADR-067](../../adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md)), 거기서도 읽지 못하면 비운다. 부모의 값으로 채우지 않는다 |
| `model` | VARCHAR(128) NULL | 자식이 돈 모델 |
| `input_tokens` | BIGINT NULL | cache 를 뺀 일반 입력 토큰 |
| `cache_read_tokens`, `cache_write_tokens` | BIGINT NULL | cache 에서 읽은 입력과 cache 에 쓴 입력 |
| `output_tokens` | BIGINT NULL | |
| `estimated_cost_micros` | BIGINT NULL | 공개 API 가격으로 환산한 금액. 통화 단위의 100만분의 1. 확인하지 못하면 비운다 |
| `actual_cost_micros` | BIGINT NULL | 부모 실행의 `cost_mode` 가 `API` 일 때만 환산액과 같은 값 |
| `cost_currency` | CHAR(3) NULL | |
| `pricing_version` | VARCHAR(32) NULL | 이 금액을 계산한 가격표 |
| `recorded_at` | DATETIME(6) NULL | 사용량을 적은 시각. `DONE` 으로 바꿀 때 채운다 |

`(execution_id, child_session_id)` 가 유일하다.
사용자, 에이전트, 달은 부모 실행에서 얻는다. 같은 값을 여기 다시 적지 않는다.
부모 실행의 토큰 칸과 달리 입력을 셋으로 나눠 적는다. 합계에 더할 때는 셋을 합쳐 부모의 `input_tokens` 와 같은 뜻으로 맞춘다.

사용량 칸이 생기기 전에 끝난 자식은 마이그레이션이 다시 `WAITING` 으로 넣는다.
이미 `DONE` 이던 줄과, 작업 줄 없이 시작 사건만 남은 자식이 대상이다. 조회 기한은 마이그레이션 시각에서 24시간이다.
에이전트가 지워졌거나 profile 이 바뀐 부모의 자식은 조회하지 않고 `EXPIRED` 로 넣는다.

## execution_skill_use

실행 하나에서 스킬 하나가 쓰인 것이 한 행이다. 스킬 호출 이력의 원천이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `execution_id` | BIGINT | 어느 실행에서 썼나 |
| `skill_name` | VARCHAR(64) | 스킬 이름 |
| `source` | VARCHAR(20) | `COMMAND` 는 사용자가 `/이름` 으로 불렀다. `MODEL` 은 모델이 스스로 `skill_view` 로 읽었다 |
| `occurred_at` | DATETIME(6) | |

`(execution_id, skill_name, source)` 에 유일 제약이 있다. 한 실행에서 모델이 같은 스킬을 여러 번 읽어도 한 행이다.
호출 횟수는 실행 수로 센다. 같은 실행에 `COMMAND` 와 `MODEL` 이 함께 있어도 1회다.
사용자, 에이전트, 대화는 `agent_execution` 과 이어 얻는다. 같은 값을 여기 다시 적지 않는다.

**스킬을 지워도 행은 남는다.** 이름으로 남아 지난 호출을 읽을 수 있다.
`MODEL` 행은 실행 사건에 스킬 이름이 실려 올 때만 생긴다.

스킬 본문과 참고 파일은 이 데이터베이스에 없다. Hermes 가 읽는 공유 디렉터리에만 있다.
누가 이 이력을 어디까지 보는지와 근거는 [ADR-034](../../adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 에 있다.

## hermes_session_binding

Hermes `delegate_task` 가 만든 하위 에이전트 session 이 어느 FOS 실행에서 시작됐는지 적는다.
profile 플러그인이 `subagent_start` hook 에서 등록한다. 근거는 [ADR-037](../../adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.

**한 번 적은 줄은 바꾸지 않는다.** 같은 대화의 다음 turn 이 시작돼도 그 하위 에이전트는 처음 origin 실행에 속한다.
대화 session 은 여러 turn 이 이어 쓰므로 여기 적지 않는다. 최상위 session 은 `agent_execution.hermes_session_id` 로 찾는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `profile_name` | VARCHAR(64) | 등록한 토큰이 증명한 profile |
| `session_id` | VARCHAR(128) | 하위 에이전트 session. Hermes 가 정한 값이다 |
| `user_id` | BIGINT | origin 실행의 `user_id`. MCP 호출의 요청자다 |
| `origin_execution_id` | BIGINT | 이 session 을 낳은 FOS 실행. 끝난 실행이어도 된다. 이 실행이나 그 루트 실행이 `CANCELLED` 면 MCP 호출을 거절한다 |
| `root_session_id` | VARCHAR(128) | 서명한 `parent_root_session_id`. MCP 호출의 서명한 루트와 다르면 거절한다 |
| `parent_session_id` | VARCHAR(128) | 이 session 을 만든 session. 최상위 session 이거나 다른 하위 에이전트 session 이다 |
| `created_at` | DATETIME(6) | 등록 시각 |

| 제약 | 까닭 |
| --- | --- |
| `UNIQUE (profile_name, session_id)` | session id 가 profile 사이에서 유일하다고 보장하지 않는다. 한 profile 안에서 한 session 은 한 origin 에만 속한다 |

외래 키는 두지 않는다. `agent_execution` 도 사용자와 대화에 외래 키를 두지 않고, 실행 줄과 사용자는 지우지 않는다. 등록은 서버가 방금 읽은 실행에서 origin 과 사용자를 옮겨 적으므로 없는 실행을 가리키지 않는다.

같은 `(profile_name, session_id)` 가 같은 부모와 루트로, 또는 같은 origin 으로 다시 오면 새 줄을 만들지 않고 성공으로 답한다.
다른 origin 이면 거절하고 덮어쓰지 않는다. 동시에 두 요청이 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 같은 규칙으로 판정한다.

하위 에이전트의 하위 에이전트는 부모 등록의 `origin_execution_id` 와 `user_id` 를 그대로 잇는다. 하위 에이전트 몫의 `agent_execution` 줄은 만들지 않는다. 하위 에이전트는 지금처럼 `execution_event` 의 `SUBAGENT_STARTED`, `SUBAGENT_COMPLETED` 로 보인다.

Control Plane 이 다시 떠도 등록 줄은 그대로다. 이미 등록한 하위 에이전트는 계속 요청자를 찾는다. 기동 정리가 다시 붙은 실행은 `RUNNING` 으로 남아 있어 그 run 이 새로 만든 최상위 자식도 등록된다. 실패로 적힌 실행의 run 이 새로 만든 최상위 자식은 도는 부모가 없어 등록되지 않는다.

## execution_context_source

실행 하나에 실은 문맥 항목의 참조다. 어느 답에 어느 기록이 들어갔는지 나중에 찾으려고 남긴다.
제목과 본문은 남기지 않는다. 항목의 뜻은 [`../context-bundle.md`](../context-bundle.md) 가 갖는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `execution_id` | BIGINT | 이 문맥을 받은 실행 |
| `position` | INT | 그 실행의 문맥 안에서의 순서. 0 부터 |
| `source` | VARCHAR(32) | 항목의 `source` |
| `source_ref` | VARCHAR(80) | 항목의 `ref`. `memory:<번호>` 처럼 원래 기록을 가리킨다 |
| `body_mode` | VARCHAR(16) | `INLINE`, `TITLE_ONLY`, `OMITTED` |
| `freshness` | VARCHAR(16) | `FRESH`, `STALE`, `UNKNOWN` |
| `created_at` | DATETIME(6) | |

기본 키는 `(execution_id, position)` 이다.
`OMITTED` 줄은 자리가 없어 빠진 Memory 항목이거나 결과를 알 수 없어 본문을 싣지 않은 승인 결과다. `MEMORY_` 로 시작하는 `OMITTED` 줄의 수는 `agent_execution.context_omitted_items` 와 같다.
실행 줄을 지우지 않으므로 이 줄도 지우지 않는다.
