# 저장 모델

색인은 마이그레이션이 갖는다. 이 문서는 표마다 칸과 유일 제약과 FK 만 적는다.

| 파일 | 표 |
| --- | --- |
| [`users-agents.md`](users-agents.md) | `app_user`, `allowed_person`, `agent`, `model_tier_definition`, `model_tier_group_setting`, `model_hidden`, `agent_token` |
| [`chat.md`](chat.md) | `conversation`, `chat_message`, `chat_pending_message`, `chat_attachment`, `chat_artifact` |
| [`execution.md`](execution.md) | `agent_execution`, `execution_event`, `subagent_usage_job`, `execution_skill_use`, `hermes_session_binding` |
| [`memory.md`](memory.md) | `memory`, `memory_revision`, `memory_collection`, `agent_memory_collection` |
| [`connector.md`](connector.md) | `connector_connection`, `connector_action`, `connector_tool_grant` |

## 모델 단계와 재조회

선택의 우선순위와 초기값은 [모델 단계와 실행 기록](../../model-tiers.md)이 정한다.
표와 칸은 [`users-agents.md`](users-agents.md), [`chat.md`](chat.md), [`execution.md`](execution.md) 의 각 절에 있다.
비밀값은 그 표들에 저장하지 않는다.

`conversation.model_selection_mode` 의 null 은 사용자와 그룹 기본값을 따른다는 뜻이다.
단계와 요청값은 실행 시작 시 복사하고 실제 제공사와 모델은 완료 시 갱신한다.
이전 실행의 `reasoning_effort_source` 는 null 로 두고 추정해 채우지 않는다.
끝난 실행의 재조회는 `finished_at` 색인을 쓴다.
재조회 작업의 완료 사건은 기존 `(execution_id, sequence)` 유일 제약을 지키며 같은 자식 완료를 중복 저장하지 않는다.
실행이나 사용자 삭제에 의한 cascade를 추가하지 않는다. 기존 실행 기록과 같은 보존 규칙을 따른다.

## 지울 때

에이전트 행은 지우지 않는다. 사용자가 에이전트를 지우면 `deleted_at` 을 적고 `enabled` 를 내린다.
`profile_managed` 가 참이면 그 Hermes profile 과 올린 스킬 디렉터리를 지우고 그 profile 의 MCP 토큰을 폐기한다.
거짓이면 운영에서 만든 profile 이라 profile 은 남긴다.
대화와 실행 기록과 스킬 호출 이력은 남는다.

대화도 지우지 않는다. 사용자가 지우면 `conversation.deleted_at` 을 적고 목록에서 숨긴다.
메시지와 실행 기록과 Hermes session 은 그대로 둔다.
아직 보내지 않은 대기 메시지(`chat_pending_message`)는 함께 지운다. 지운 대화에는 보낼 곳이 없다.
사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
실행 기록이 에이전트와 대화를 가리키고 있고, 기록은 남아야 한다.

사용자를 지우는 흐름은 아직 없다.

페르소나는 이 데이터베이스에 없다. 본문은 그 profile 의 `SOUL.md` 가 갖는다.
근거는 [ADR-019](../../adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) 에 있다.

첨부도 행을 지우지 않는다. 파일만 지우고 `deleted_at` 을 적는다.
결과물(`chat_artifact`)도 같다.
하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다. 실행 기록과 함께 남는다.
그 자리에 사진이 있었다는 것이 남아야 지난 대화를 읽을 수 있다.

Memory 는 줄을 지운다. 지우기 전에 마지막 값을 `memory_revision` 에 `DELETED` 로 남기므로 본문은 그 표에 남는다.
화면의 삭제는 목록과 주입에서 빼는 것이고, 본문을 완전히 없애는 길은 아직 없다.
에이전트를 지워도 `agent_memory_collection` 의 줄은 그대로 둔다. 지운 에이전트는 실행되지 않으므로 그 줄을 읽는 자리가 없다.

허용 목록에서 빼는 것도 지우지 않고 `enabled` 를 내린다.
그 사람의 `app_user` 와 실행 기록은 그대로 둔다.
그 사람의 Hermes profile 도 지우지 않는다. 다시 들일 때 그것을 다시 만들지 않아도 된다.
