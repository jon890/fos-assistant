# 예약 작업

예약 작업과 그 시각, 발화 한 번을 저장하는 표 셋에서 마이그레이션만 읽어서는 알 수 없는 칸의 뜻과 제약의 까닭을 갖는다.
칸과 타입은 `V68__task.sql`, `V77__ProactiveScheduleTask`, `V20261008104800__task_model_tier.sql` 이 갖고, 값의 목록은 각 enum 이 갖는다.
언제 발화하고 어떻게 시작하는지는 [`../task.md`](../task.md) 가 갖는다.

## task

예약 작업 하나다. 근거는 [ADR-076](../../../backend/docs/adr/ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md) 이다.

- `owner_user_id` 는 누구의 권한으로 도는가이고 바뀌지 않는다. `agent_id` 는 주인이 고칠 수 있다
- `kind` 는 `TURN` 이 보통 예약 turn, `CHECK` 가 매일 깨우기다. `CHECK` 는 `instruction` 과 `model_tier` 를 비운다
- `conversation_id` 는 `SINGLE` 일 때 결과를 쌓는 대화다. 첫 발화가 만들기 전에는 비어 있고, `NEW_PER_RUN` 은 늘 비어 있다
- `model_tier` 가 비면 발화가 대화의 모델 선택을 건드리지 않는다
- `archived_at` 은 `state` 가 `ARCHIVED` 일 때만 찬다

**`check_owner_user_id`, `check_agent_id` 생성 열은 `CHECK` 일 때만 주인과 에이전트 번호를 채운다.** 두 칸의 유일 제약으로 깨우기 설정 하나를 강제한다. `TURN` 줄은 두 칸이 비어 있어 걸리지 않는다.

- 외래 키는 `owner_user_id` 에만 둔다. 에이전트와 대화는 지워도 행이 남는 표라 걸지 않는다
- 지우면 `ARCHIVED` 로 둔다. 줄은 남는다. 발화 기록과 대화가 이 줄을 가리킨다

`CHECK` 는 켜고 끄기를 `ACTIVE`, `PAUSED` 로 저장하며 같은 설정 줄을 다시 쓴다.
대화는 점검 대화를 쓰므로 `conversation_mode`, `conversation_id` 로 대화를 만들지 않는다.
일반 예약 작업 API 는 `TURN` 만 읽고 바꾼다.

## task_trigger

작업의 시각이다. 지금은 작업 하나에 하나다. 근거는 [ADR-077](../../../backend/docs/adr/ADR-077-발화는-trigger-와-예정-시각의-유일-제약으로-한-번만-만들고-놓친-발화는-작업마다-정한다.md) 이다.
`fire_at` 과 `next_fire_at` 은 UTC 이고, 한 번 발화한 `ONCE` 는 `next_fire_at` 이 비어 있다. `last_fired_at` 은 마지막으로 처리한 예정 시각이다.

- `task_id` 의 유니크는 작업 하나에 시각 하나라는 지금의 규칙이다. 다른 종류의 trigger 를 더하는 단계가 이 제약을 바꾼다
- 시각을 고치면 같은 줄을 고친다. 발화 기록의 유일 제약이 이 줄의 번호를 쓰므로 줄을 새로 만들지 않는다

## task_run

발화 한 번이다.

- `owner_user_id` 는 그 발화 때의 작업 주인이다. 하루 발화 수를 조인 없이 세려고 따로 둔다
- `reason` 은 `SKIPPED` 와 `FAILED` 의 까닭이다. `SUCCEEDED` 는 비어 있거나, 답이 `[SILENT]` 뿐이면 `NOTHING_TO_REPORT` 다. 값의 목록은 `TaskRunReason` 이 갖는다
- `conversation_id` 는 결과를 남긴 대화다. 시작 전에 끝난 줄은 비어 있을 수 있다
- `execution_id` 는 이 발화의 루트 `agent_execution` 이고, turn 이 끝난 모양을 받았을 때만 찬다
- `proactive_check_id` 는 `CHECK` 발화가 연 점검 줄이다. 저장과 발화 연결을 한 트랜잭션으로 끝낸다. 다음 tick 과 기동 복구가 점검 결과를 동기화한다

- `(trigger_id, scheduled_for)` 가 유니크다. 같은 예정 시각의 발화는 서버를 다시 띄워도 하나다
- 외래 키는 `task_id` 와 `trigger_id` 에 둔다. 작업과 시각은 지우지 않으므로 걸어도 된다
- `agent_execution` 은 바꾸지 않는다. 이 줄의 `execution_id` 가 가리키고, 그 아래 위임과 승인은 실행 트리로 이어진다

## conversation 에 더하는 칸

`conversation.task_id` 는 이 대화를 만든 예약 작업이고, 사용자가 연 대화는 비어 있다.
근거는 [ADR-078](../../adr/ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md) 이다. 외래 키는 두지 않는다. `conversation.agent_id` 와 같은 까닭이다.
