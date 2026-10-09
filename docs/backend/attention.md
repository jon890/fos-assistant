# 먼저 알리기와 지금 화면의 판정

사용자가 묻지 않았는데 보일 항목의 후보, 판정 표, 억제와 중복 규칙, 사용자 제어, 지표, API 를 갖는다.
결정은 [ADR-072](../adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md) 와 [ADR-074](../../web/docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md) 에 있다.
화면의 배치와 문구는 [`web/docs/prd.md`](../../web/docs/prd.md) 가 갖는다.

## 패키지

판정은 최상위 패키지 `attention` 이 맡는다. 층 순서의 맨 위(`task` 위)에 둔다.
실행 기록(`usage`), Memory 제안(`memory`), 대화(`chat`), 할 일(`followup`), 승인 줄(`connector`), 살펴보기 보고와 매일 루프 판정(`proactive`)을 모두 읽기 때문이다.
`attention` 은 읽기만 하고 그 패키지들의 기록을 고치지 않는다. 고치는 동작은 카드의 단추가 각 패키지의 기존 API 로 보낸다.
**`attention` 은 다른 패키지의 `infra` 를 import 하지 않는다.** 원래 기록은 그 패키지의 `application` 에 둔 읽기 메서드로 읽는다. 저장 방식이 바뀌어도 판정을 고치지 않게 하려는 것이다.

## 판정 셋

| 판정 | 뜻 |
| --- | --- |
| `NOW` | 건수에 세고 카드 맨 위에 둔다 |
| `LATER` | 카드 안에 보이고 건수에 세지 않는다 |
| `SUPPRESSED` | 응답에 넣지 않는다 |

**기본값은 `SUPPRESSED` 다.** 아래 표의 후보 조건을 채운 기록만 판정을 받는다.

## 후보와 trigger

| `trigger` | 카드 | 원래 기록 | 후보 조건 | `NOW` 조건 | 해결된 상태 | `itemKey` | `stateKey` 의 재료 | 확신도 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `EXECUTION_FAILED` | `failures` | 사용자가 보낸 대화 turn 의 루트 실행. `conversation_id` 가 있고 `parent_execution_id` 가 비어 있다. 자동 turn 의 실패는 `DELIVERY_FAILED` 가 맡는다 | `FAILED` 이고 `finished_at` 이 `failure-window` 안 | 늘 | 같은 대화에 그 뒤 `SUCCEEDED` 루트 실행이 있다. 대화를 지웠다 | `conversation:<대화 공개 식별자>` | 그 대화의 마지막 실패 실행 번호 | `CONTROL_PLANE` |
| `DELIVERY_FAILED` | `failures` | 결과 전달 묶음 `result_delivery`([ADR-075](../adr/ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md)). 사용자는 그 대화의 `user_id` 다 | `FAILED` 이고 `updated_at` 이 `failure-window` 안. `DELIVERING` 은 시도가 도는 중이라 후보가 아니다 | 늘 | `DELIVERED`, `STOPPED`. 대화를 지웠다 | 위와 같은 대화 열쇠. 실패한 turn 과 한 항목으로 합친다 | 묶음 번호와 `attempt_count`. 실패한 turn 과 합치면 그 실행 번호도 함께 | `CONTROL_PLANE` |
| `APPROVAL_PENDING` | `needs_me` | `connector_action` | `PENDING` 이고 `expires_at` 전. 요청자의 지우지 않은 대화에 속한다 | 늘 | 승인, 거절, 만료 | `connector_action:<공개 식별자>` | `status` | `CONTROL_PLANE` |
| `MEMORY_PROPOSED` | `needs_me` | 주인의 `USER` Memory | `PROPOSED` | 아니다 | 받아들임, 거절 | `memory:<번호>` | `revision` | `MODEL_INFERRED` |
| `FOLLOW_UP_PROPOSED` | `needs_me` | `follow_up` | `PROPOSED` | 아니다 | 받아들임, 거절 | `follow_up:<공개 식별자>` | `updated_at` | `MODEL_INFERRED` |
| `FOLLOW_UP_OPEN` | `needs_me` | `follow_up` | `OPEN` | 기한이 `due-soon` 안이거나 지났다. 또는 연결한 대화에 결과가 도착했다 | 끝냄, 그만둠 | `follow_up:<공개 식별자>` | `updated_at`, 기한 구간(없음, `DUE_SOON`, `OVERDUE`), 연결한 대화의 마지막 결과 전달 시각 | `USER_CONFIRMED` |
| `DELEGATION_RUNNING` | `delegated` | 주인의 위임 실행. `delegation_key` 가 있다 | `RUNNING`. 요청자의 지우지 않은 대화에 속한다 | 시작한 지 `long-running-after` 를 넘었다 | 끝남 | `execution:<번호>` | `status` | `CONTROL_PLANE` |
| `DELEGATION_FINISHED` | `delegated` | 주인의 위임 실행 | 끝났고 `finished_at` 이 `delegated-window` 안. 요청자의 지우지 않은 대화에 속한다 | 아니다 | 없다 | `execution:<번호>` | `status` | `CONTROL_PLANE` |
| `CONVERSATION_RECENT` | `continue` | 주인의 대화. `deleted_at` 이 비어 있다 | `updated_at` 순으로 `continue-count` 개 | 아니다 | 없다 | `conversation:<대화 공개 식별자>` | `updated_at` | `CONTROL_PLANE` |
| `PROBLEM_SURFACED` | `needs_me` | 요청자의 [매일 루프](proactive-loop.md) 시도(`DECIDED`)가 만든 평가의 `proactive_autonomy_decision` 가운데 `SURFACE`, `ASK_APPROVAL` 판정 | 시도가 `surface-window` 안에 있고, 원천 점검 대화를 지우지 않았고, 그 판정에 사용자의 「받아들임」 이나 「관심 없음」 이 없다. 같은 문제 키는 가장 최근 판정 하나만, 최근 것부터 `surface-max-items` 개까지 | 아니다 | 받아들임, 관심 없음, 점검 대화 삭제, 창이 지남 | `autonomy_decision:<번호>` | 판정 번호 | `MODEL_INFERRED` |
| `PROACTIVE_REPORT` | `reports` | 요청자의 `proactive_check` | 검사를 거친 보고가 있고 점검 대화가 지워지지 않았다 | 아니다 | 보고 열람 | `proactive_check:<번호>` | 보고 번호와 완료 시각 | `MODEL_INFERRED` |

**같은 대화에 실패한 turn 과 결과 전달 실패가 함께 있으면 한 항목으로 합친다.**
`trigger` 는 둘 가운데 더 최근 쪽이다. 묶음의 `updated_at` 이 실행의 `finished_at` 보다 뒤면 `DELIVERY_FAILED` 이고, 같거나 앞이면 `EXECUTION_FAILED` 다.
`signals` 는 `NOT_RETRIED` 와 `DELIVERY_NOT_DONE` 을 이 순서로 함께 담고, `sources` 도 `EXECUTION_STATE` 다음에 `RESULT_DELIVERY` 를 담는다.
`at` 은 `trigger` 로 고른 쪽의 시각이다.
`stateKey` 는 `<trigger 이름>|<재료>` 글의 SHA-256 앞 16바이트를 16진수로 쓴 것이다. 앞 글은 실행이 있으면 `EXECUTION_FAILED` 로 고정한다.
그래서 실패한 turn 만이면 `EXECUTION_FAILED|<실행 번호>` 이고, 결과 전달 실패만이면 `DELIVERY_FAILED|<묶음 번호>|<attempt_count>` 이고, 둘 다면 `EXECUTION_FAILED|<실행 번호>|DELIVERY_FAILED|<묶음 번호>|<attempt_count>` 다.
더 최근인 쪽만 바뀌어서는 `stateKey` 가 바뀌지 않아 숨긴 항목이 다시 보이지 않는다.

승인 대기와 맡긴 일은 요청자의 지우지 않은 대화에 속한 것만 후보가 된다.
대화 없이 생긴 승인 대기는 보이지 않는다. 승인 카드가 대화 안에만 있어 「대화에서 보기」 로 갈 곳이 없기 때문이다.

「사용자가 보낸 turn」 은 그 실행의 `started_at` 이전에 그 대화에 저장된 메시지 가운데 `ASSISTANT` 가 아닌 가장 최근 메시지의 `role` 이 `USER` 라는 뜻이다. 다시 생성도 든다. 자동 turn 은 그 메시지가 `SYSTEM` 이라 빠진다. 실패한 turn 에는 답 메시지가 없을 수 있어 실행의 시작 시각으로 질문을 찾는다. 예약 작업의 turn 도 든다. 예약 turn 은 알림 줄 다음에 지시를 `USER` 메시지로 저장하고, 사용자가 맡긴 일이 실패한 것이라 화면에 올린다.

「연결한 대화에 결과가 도착했다」 는 그 대화의 맡긴 일(`agent_execution.result_delivered_at`)이나 승인한 동작(`connector_action.result_delivered_at`)의 결과가 할 일의 `accepted_at` 보다 뒤에 전해졌다는 뜻이다.
`SYSTEM` 메시지로 판정하지 않는다. 자동 turn 한도 안내와 승인 거절이나 만료 알림도 `SYSTEM` 메시지라 결과 도착과 구분하지 못한다.
사용자가 그 대화에서 주고받는 답은 세지 않는다. 대화하는 동안 할 일이 계속 `NOW` 가 되지 않게 하려는 것이다.
숨기면 `stateKey` 가 그 결과 전달 시각과 기한 구간을 담아, 다음 결과가 전해지거나 기한이 다가오거나 지날 때까지 보이지 않는다.
연결한 대화를 지웠으면 그 할 일은 남고 `conversationId` 는 `null` 이며 결과 도착을 보지 않는다.

할 일의 `at` 은 제안이면 `created_at`, 열린 할 일이면 `updated_at` 이다.
연결한 대화의 결과 도착이 그보다 뒤면 그 전달 시각이다.
`signals` 는 `OVERDUE` 나 `DUE_SOON`, `LINKED_UPDATE`, `WAITING` 순이고, `WAITING` 은 제안에도 붙는다.

`MEMORY_PROPOSED` 는 기억 메뉴의 제안 건수에 이미 센다. 지금 화면의 건수에는 세지 않는다.

`DELIVERY_FAILED` 는 결과 전달 묶음의 상태를 읽기만 한다. 그 상태를 저장하고 다시 전달하는 일은 `chat` 의 `ResultDeliveryRecorder` 가 갖는다.

## 억제 신호

아래를 위에서부터 보고 하나라도 맞으면 `SUPPRESSED` 다.

| 순서 | 신호 | 맞는 경우 |
| --- | --- | --- |
| 1 | `HIDDEN` | 사용자가 같은 카드의 같은 `itemKey` 와 같은 `stateKey` 를 숨겼다 |
| 2 | `SNOOZED` | 사용자가 같은 카드의 그 항목을 미룬 기한이 아직 지나지 않았다 |
| 3 | `RESOLVED` | 위 표의 「해결된 상태」 다 |
| 4 | `TOO_OLD` | 후보 조건의 기간을 벗어났다 |
| 5 | `DUPLICATE` | 같은 `itemKey` 가 앞선 카드에 이미 있다. 카드 순서는 `failures`, `needs_me`, `delegated`, `continue` 다 |

`MODEL_INFERRED` 항목은 위에 걸리지 않아도 `NOW` 가 되지 못하고 `LATER` 다.

**원래 기록을 읽지 못한 source 는 추정하지 않는다.** 그 source 의 카드만 `status: UNAVAILABLE` 로 내고 다른 카드는 그대로 낸다.

## 기준값

설정 접두사는 `assistant.attention` 이다.

| 설정 | 기본값 | 뜻 |
| --- | --- | --- |
| `failure-window` | 7일 | 실패한 turn 을 보이는 기간 |
| `delegated-window` | 24시간 | 끝난 위임을 보이는 기간 |
| `long-running-after` | 30분 | 도는 위임이 `NOW` 가 되는 시간 |
| `due-soon` | 24시간 | 기한이 이만큼 남으면 `NOW` |
| `continue-count` | 5 | 이어서 하기 카드의 항목 상한. 이 카드에는 `max-items-per-card` 대신 이 값을 쓴다 |
| `max-items-per-card` | 10 | 카드 하나의 항목 상한. 넘으면 `moreCount` 로 센다 |
| `snooze-max` | 8일 | 미루기 기한의 상한 |
| `event-retention` | 90일 | 지표 사건을 남기는 기간 |
| `cleanup-cron` | `0 30 4 * * *` | 보관 기간이 지난 지표 사건을 지우는 시각 |

## 왜 보였는가

항목마다 아래 칸을 낸다. 화면은 이 코드를 정해진 문구로 그린다([`web/docs/prd.md`](../../web/docs/prd.md) 의 「이유 문구」).

```json
{
  "trigger": "FOLLOW_UP_OPEN",
  "signals": ["DUE_SOON"],
  "confidence": "USER_CONFIRMED",
  "sources": [
    { "source": "FOLLOW_UP", "ref": "follow_up:5f0c…", "asOf": "2026-10-04T09:00:00Z" }
  ]
}
```

| `signals` 의 값 | 뜻 |
| --- | --- |
| `NOT_RETRIED` | 실패 뒤 같은 대화에서 다시 돌리지 않았다 |
| `DELIVERY_NOT_DONE` | 결과는 저장됐지만 부모 답을 만들지 못했다 |
| `EXPIRES_SOON` | 승인 기한이 6시간 안이다 |
| `DUE_SOON`, `OVERDUE` | 할 일의 기한이 다가왔다, 지났다 |
| `LINKED_UPDATE` | 할 일에 연결한 대화에 결과가 도착했다 |
| `WAITING` | 할 일이 기다리는 중이다 |
| `LONG_RUNNING` | 맡긴 일이 오래 돌고 있다 |

「출처 이름」 은 `sources[].source` 의 글이다. 아래 표가 전부다.
`EXECUTION_STATE` 와 `FOLLOW_UP` 은 [`context-bundle.md`](context-bundle.md) 「참여하는 source」 의 이름이고, 나머지는 판정에만 쓰는 이름이다.

| `source` | `ref` | `asOf` | 쓰는 trigger |
| --- | --- | --- | --- |
| `EXECUTION_STATE` | `execution:<실행 번호>` | 판정 시각(`readAt`) | `EXECUTION_FAILED`, `DELEGATION_RUNNING`, `DELEGATION_FINISHED` |
| `APPROVAL_REQUEST` | `connector_action:<공개 식별자>` | 승인 줄의 `created_at` | `APPROVAL_PENDING` |
| `MEMORY_PROPOSAL` | `memory:<번호>` | Memory 의 `updated_at` | `MEMORY_PROPOSED` |
| `CONVERSATION` | `conversation:<공개 식별자>` | 대화의 `updated_at` | `CONVERSATION_RECENT` |
| `FOLLOW_UP` | `follow_up:<공개 식별자>` | 할 일의 `updated_at` | `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` |
| `RESULT_DELIVERY` | `result_delivery:<묶음 번호>` | 묶음의 `updated_at` | `DELIVERY_FAILED` |
| `AUTONOMY_DECISION` | `autonomy_decision:<번호>` | 판정의 `created_at` | `PROBLEM_SURFACED` |

`sources` 의 `ref` 는 문맥 묶음의 참조와 같은 형식이다([`context-bundle.md`](context-bundle.md) 의 「항목의 칸」).
**응답에 실행의 오류 코드, 모델, 금액을 싣지 않는다.** 일반 경로의 응답이라 역할과 상관없이 뺀다([ADR-063](../adr/ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md)).

## 카드의 단추와 승인 경계

먼저 알리기는 보이기만 한다. 단추는 이미 있는 경로로 보내고, 사용자가 누를 때만 돈다.

| 항목 | 단추 | 가는 곳 |
| --- | --- | --- |
| 실패한 turn | 대화 열기 | `/chat/{대화 공개 식별자}`. 다시 보낼지는 사람이 대화에서 정한다 |
| 결과 전달 실패 | 대화 열기 | `/chat/{대화 공개 식별자}`. 다시 전달은 그 대화의 알림 줄 아래 「결과 다시 전달」 이 한다. 응답에 묶음 번호를 싣지 않는다 |
| 승인 대기 | 대화에서 보기 | 그 대화의 승인 카드. 승인과 거절은 기존 `POST /api/v1/connector-actions/{actionId}/approve`, `.../reject` 다 |
| Memory 제안 | 기억에서 보기 | `/memory` |
| 할 일 제안 | 받아들이기, 거절, 고치기 | [`follow-up.md`](follow-up.md) 의 API |
| 열린 할 일 | 끝냄, 그만둠, 고치기 | [`follow-up.md`](follow-up.md) 의 API |
| 맡긴 일 | 작업 과정 보기 | `/executions/{번호}` |
| 먼저 다룰 문제 | 점검 대화에서 보기, 받아들임, 관심 없음 | `/chat/{점검 대화 공개 식별자}`. 반응은 `PUT /api/v1/autonomy-decisions/{id}/reaction`([매일 루프](proactive-loop.md)의 「사용자에게 보이는 것」). 반응을 기록할 뿐 할 일, 승인 줄, 실행을 만들지 않는다 |
| 이어서 하기 | 제목 링크(단추 없음) | `/chat/{대화 공개 식별자}` |

**판정이 시작하지 않는 것**: Hermes 실행, 커넥터 호출, Memory 쓰기, 할 일 만들기.
사용자의 확인 없이 무언가를 실행하는 proactive 동작은 이 문서의 범위 밖이다. 열려면 새 ADR 과 ADR-050 같은 승인 줄이 먼저 있어야 한다.

## API

모두 웹 JWT 로 부르고 요청자 자기 것만 읽고 쓴다.

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/attention` | 카드 다섯과 항목, `nowCount`, `readAt`. 응답에 실린 `NOW` 와 `LATER` 항목마다 `SHOWN` 사건을 한 번 남긴다 |
| `GET /api/v1/attention/summary` | `{ "nowCount": 3 }`. 사이드바와 홈의 한 줄이 읽는다. 사건을 남기지 않는다 |
| `POST /api/v1/attention/hide` | 본문 `{ card, itemKey, stateKey }`. 그 카드에서 그 상태가 바뀔 때까지 숨기고 `HIDDEN` 사건을 남긴다 |
| `POST /api/v1/attention/snooze` | 본문 `{ card, itemKey, until }`. `until` 은 지금보다 뒤이고 `snooze-max` 안이어야 한다. 아니면 400 `VALIDATION_FAILED`. 받으면 `SNOOZED` 사건을 남긴다 |
| `POST /api/v1/attention/restore` | 본문 `{ card, itemKey }`. 그 카드의 숨기기와 미루기를 지운다 |
| `POST /api/v1/attention/events` | 본문 `{ itemKey, stateKey, type }`. `type` 은 `OPENED` 나 `ACTED` 다 |
| `GET /api/v1/admin/attention/metrics?days=30` | 관리자만. 아래 「지표」 를 `trigger` 별로 센다. 제목과 항목 열쇠를 내지 않는다. `days` 는 1 부터 90 까지이고 벗어나면 400 `VALIDATION_FAILED` 다 |

`itemKey` 에 `:` 와 UUID 가 들어 있어 경로 대신 본문으로 받는다.
**제어는 카드마다 따로 둔다.** 같은 대화가 실패 카드와 이어서 하기 카드에 함께 열쇠로 쓰여도, 한 카드에서 숨긴 것이 다른 카드의 제어를 덮어쓰지 않는다.
`hide`, `snooze`, `restore`, `events` 는 성공하면 본문 없이 204 로 답한다. 같은 요청을 다시 보내도 204 다.
**열쇠의 길이를 먼저 본다.** `itemKey` 가 비었거나 80자를 넘거나, `stateKey` 가 비었거나 64자를 넘으면 후보를 읽기 전에 400 `VALIDATION_FAILED` 다. `hide`, `snooze`, `restore`, `events` 가 모두 그렇다. 길이는 `attention_control` 과 `attention_event` 의 칸 길이와 같다.
`hide`, `snooze` 의 `itemKey` 가 지금 요청자의 후보에 없으면 404 `ATTENTION_ITEM_NOT_FOUND` 다. `events` 는 지금 후보에 있거나, 그 요청자에게 `itemKey` 와 `stateKey` 가 같은 `SHOWN` 사건이 있으면 받는다. 받아들이기와 끝냄처럼 동작이 성공하면 그 항목이 후보에서 빠지므로, 그 뒤에 보내는 `ACTED` 를 잃지 않게 하려는 것이다. 둘 다 아니면 404 `ATTENTION_ITEM_NOT_FOUND` 다. 남의 항목과 없는 항목을 같은 응답으로 숨긴다. `restore` 는 지운 것이 없어도 204 다.
카드의 출처를 모두 읽지 못하면 그 카드의 후보가 비어 있어, 그 카드 항목의 `hide` 와 `snooze` 도 404 `ATTENTION_ITEM_NOT_FOUND` 다.
여러 출처 가운데 일부만 실패해 카드가 `UNAVAILABLE` 이면 읽은 출처의 후보는 받는다.

**숨기기와 미루기를 읽지 못하면 두 `GET` 이 통째로 실패한다.** `attention_control` 을 읽지 못한 채 판정하면 숨긴 항목이 다시 보이기 때문이다. 출처 하나를 읽지 못했을 때 그 카드만 `UNAVAILABLE` 로 내는 것과 다르다.

응답의 모양은 아래와 같다.

```json
{
  "readAt": "2026-10-04T09:00:00Z",
  "nowCount": 2,
  "cards": [
    {
      "key": "failures",
      "status": "OK",
      "nowCount": 1,
      "moreCount": 0,
      "items": [
        {
          "itemKey": "conversation:7b1e…",
          "stateKey": "3f9a…",
          "attention": "NOW",
          "channel": "IN_APP",
          "title": "주간 장보기 목록 정리",
          "conversationId": "7b1e…",
          "agentName": "집안일 도우미",
          "at": "2026-10-03T13:10:00Z",
          "why": { "trigger": "EXECUTION_FAILED", "signals": ["NOT_RETRIED"], "confidence": "CONTROL_PLANE", "sources": [{ "source": "EXECUTION_STATE", "ref": "execution:812", "asOf": "2026-10-04T09:00:00Z" }] },
          "actionId": null,
          "execution": null,
          "followUp": null
        }
      ]
    }
  ]
}
```

**건수는 서버가 한 가지로 센다.** 카드의 `nowCount` 는 그 카드에서 `NOW` 인 항목 수이고 상한으로 자르기 전에 센다. 응답 맨 위의 `nowCount` 와 `summary` 의 `nowCount` 는 카드 `nowCount` 의 합이다. 화면은 카드 배지와 사이드바와 홈의 한 줄에 이 값만 쓰고, 보이는 항목을 다시 세지 않는다. 그래서 사이드바의 수는 늘 카드 배지의 합과 같다. 상한 때문에 보이지 않는 `NOW` 항목은 `moreCount` 에 함께 든다. 이어서 하기 카드의 `moreCount` 는 최근 대화 `continue-count` 더하기 `max-items-per-card` 개 안에서 센 수다. 화면은 이 수를 링크 없는 글로만 그린다.

`title` 은 대화 제목이나 할 일 제목이나 승인 줄의 동작 이름이고, 먼저 다룰 문제면 모델이 쓴 문제 글이다. 화면은 평문으로 그린다(ADR-009).
`channel` 은 그 항목을 보이는 길이다. 화면 안에서만 보이므로 값은 늘 `IN_APP` 하나다(ADR-072).

항목 종류에 따라 아래 칸을 더 채운다. 해당하지 않으면 `null` 이다. 화면이 `itemKey` 를 잘라 식별자를 얻지 않게 하려는 것이다.

| 칸 | 채우는 항목 | 담는 것 |
| --- | --- | --- |
| `execution` | `DELEGATION_RUNNING`, `DELEGATION_FINISHED` | `{ id, status }`. `status` 는 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `followUp` | `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` | `{ id, dueAt, waiting, proposed, agentProposed }`. `id` 는 할 일의 공개 식별자. `proposed` 는 아직 받아들이지 않은 제안인지, `agentProposed` 는 에이전트가 제안한 출처인지다 |
| `report` | `PROACTIVE_REPORT` | `{ checkId, agentCode, changed, done, evidence, needsApproval, next }`. 보고는 `LATER` 이며 승인 대기의 건수는 기존 `APPROVAL_PENDING` 에서 센다 |
| `actionId` | `APPROVAL_PENDING` | 승인 줄의 공개 식별자 |
| `problem` | `PROBLEM_SURFACED` | `{ decisionId, level, action }`. `level` 은 `SURFACE` 나 `ASK_APPROVAL`, `action` 은 제안한 다음 행동 글이다. 문제 글은 `title` 이다. 둘 다 모델이 쓴 글이라 평문으로 그린다 |

## 웹 알림과의 경계

웹 알림([ADR-070](../adr/ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md))은 일어난 일의 사건 목록이고 읽음 상태를 갖는다. 이 문서의 판정은 아직 남은 일을 현재 상태에서 계산한 view 다.
같은 승인 대기가 알림 목록과 지금 화면에 함께 보일 수 있다.
**지금 화면에서 그 승인을 처리해도 알림의 읽음 상태는 바꾸지 않는다.** 알림도 원인의 상태를 따라 바뀌지 않는다. 알림은 사람이 그 알림을 누를 때 읽음이 된다.
이 판정은 `notification` 표를 읽거나 쓰지 않는다.

## 저장

표의 칸은 [`schema/attention.md`](schema/attention.md) 가 갖는다.

- `attention_control`: 사용자의 숨기기와 미루기. 한 사용자의 한 카드의 한 `itemKey` 에 한 줄이다
- `attention_event`: 지표 사건. 같은 사용자, `itemKey`, `stateKey`, `type`, 판정(`attention`)은 한 줄만 남긴다. 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면 `NOW` 의 `SHOWN` 이 따로 남는다. 모든 사건 종류가 같은 규칙이다. 같은 줄이 이미 있으면 넣지 않고, 줄마다 따로 커밋해 한 줄의 충돌이 다른 줄이나 제어 줄을 되돌리지 않는다
- `attention_event` 의 판정 칸은 셋 가운데 먼저 맞는 것이다. 지금 응답에 그 항목이 있으면 그 판정, 없으면 그 항목의 가장 최근 `SHOWN` 의 판정(`OPENED`, `ACTED` 는 같은 `stateKey` 의 `SHOWN` 만 본다), 그것도 없으면 `SUPPRESSED` 다. 차례는 [`schema/attention.md`](schema/attention.md) 의 「attention_event」 에 있다

원래 기록을 지워도 이 두 표의 줄은 남는다. 열쇠가 가리키는 기록이 없으면 판정 후보가 되지 않아 보이지 않는다.
`attention_event` 는 `event-retention` 이 지난 줄을 하루 한 번 지운다.

## 지표

#160 의 알림 피로와 #161 의 UX 지표를 `attention_event` 로 센다.

| 지표 | 계산 |
| --- | --- |
| 숨김 비율 | `HIDDEN` 이 있는 항목 수 ÷ `SHOWN` 항목 수 |
| 미루기 비율 | `SNOOZED` ÷ `SHOWN` |
| 행동 비율 | `OPENED` 나 `ACTED` 가 있는 항목 ÷ `SHOWN` |
| `NOW` 의 헛보임 | `NOW` 로 보였다가 행동 없이 숨긴 항목 ÷ `NOW` 로 보인 항목. false positive 의 대리값이다 |
| 첫 행동까지 시간 | 같은 항목의 첫 `SHOWN` 에서 첫 `OPENED` 나 `ACTED` 까지의 중앙값. #161 의 time-to-first-useful-action 이다 |
| 오래된 항목 비율 | `SHOWN` 때 출처의 신선도가 `STALE` 이던 항목 ÷ `SHOWN` |

`GET /api/v1/admin/attention/metrics` 는 비율을 내지 않고 위 계산의 분자와 분모를 센 수로 낸다.

```json
{
  "days": 30,
  "rows": [
    { "trigger": "EXECUTION_FAILED", "shown": 12, "hidden": 3, "snoozed": 1, "acted": 7, "nowShown": 12, "nowHiddenWithoutAction": 2, "staleShown": 0, "medianSecondsToFirstAction": 540 }
  ]
}
```

**항목 하나는 `(사용자, itemKey, stateKey)` 다.** 기간은 지금부터 `days` 일 전 이후에 남긴 사건이다.
기간 안에 `SHOWN` 이 있는 항목만 세고, 그 항목의 `trigger` 는 기간 안의 첫 `SHOWN` 의 것이다.
`rows` 는 `trigger` 마다 한 줄이고 위 「후보와 trigger」 표의 순서다. 보인 항목이 없는 `trigger` 는 줄이 없다.

| 칸 | 뜻 |
| --- | --- |
| `days` | 센 기간의 일 수. 요청의 `days` 다 |
| `rows[].trigger` | 그 줄의 `trigger` |
| `rows[].shown` | `SHOWN` 이 있는 항목 수 |
| `rows[].hidden` | 그 가운데 `HIDDEN` 이 있는 항목 수 |
| `rows[].snoozed` | 그 가운데 `SNOOZED` 가 있는 항목 수 |
| `rows[].acted` | 그 가운데 `OPENED` 나 `ACTED` 가 있는 항목 수 |
| `rows[].nowShown` | 그 가운데 `NOW` 로 보인 `SHOWN` 이 있는 항목 수 |
| `rows[].nowHiddenWithoutAction` | `nowShown` 가운데 `OPENED` 와 `ACTED` 없이 `HIDDEN` 이 있는 항목 수 |
| `rows[].staleShown` | 그 가운데 출처가 `STALE` 이던 `SHOWN` 이 있는 항목 수 |
| `rows[].medianSecondsToFirstAction` | 첫 `SHOWN` 에서 첫 `OPENED` 나 `ACTED` 까지 걸린 초의 중앙값. 짝수 개면 가운데 둘의 평균을 내림한다. 첫 행동이 첫 `SHOWN` 보다 앞이면 0 초로 센다. 행동한 항목이 없으면 `null` |

지금의 후보는 모두 판정할 때 읽은 기록이라 신선도가 늘 `FRESH` 다. 오래된 항목 비율은 결과 source 가 후보에 들어오기 전까지 0 이다.

**아직 재지 않는 것**: 지금 화면을 본 뒤 같은 것을 찾으려고 대화나 검색으로 돌아간 비율이다. 화면 사이의 이동을 기록하는 길이 없다.
첫 반응 시간은 [`../model-tiers.md`](../model-tiers.md) 의 「첫 반응 시간」 이 갖는다.
