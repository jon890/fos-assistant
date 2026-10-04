# 먼저 알리기와 지금 화면의 판정

사용자가 묻지 않았는데 보일 항목의 후보, 판정 표, 억제와 중복 규칙, 사용자 제어, 지표, API 를 갖는다.
결정은 [ADR-072](../adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md) 와 [ADR-074](../adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md) 에 있다.
화면의 배치와 문구는 [`../frontend/now.md`](../frontend/now.md) 가 갖는다.

**아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.

## 패키지

판정은 새 최상위 패키지 `attention` 이 맡는다. 층 순서의 맨 위(`connector` 위)에 둔다.
실행 기록(`usage`), Memory 제안(`memory`), 대화(`chat`), 할 일(`followup`), 승인 줄(`connector`)을 모두 읽기 때문이다.
`attention` 은 읽기만 하고 그 패키지들의 기록을 고치지 않는다. 고치는 동작은 카드의 단추가 각 패키지의 기존 API 로 보낸다.

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
| `EXECUTION_FAILED` | `failures` | 대화 turn 의 루트 실행. `conversation_id` 가 있고 `parent_execution_id` 가 비어 있다 | `FAILED` 이고 `finished_at` 이 `failure-window` 안 | 늘 | 같은 대화에 그 뒤 `SUCCEEDED` 루트 실행이 있다. 대화를 지웠다 | `conversation:<대화 공개 식별자>` | 그 대화의 마지막 실패 실행 번호 | `CONTROL_PLANE` |
| `DELIVERY_FAILED` | `failures` | #162 가 저장하는 결과 전달 상태 | #162 가 정한 실패 상태 | 늘 | #162 가 정한 완료나 의도적 중단 | 위와 같은 대화 열쇠. 실패한 turn 과 한 항목으로 합친다 | 마지막 전달 시도의 번호 | `CONTROL_PLANE` |
| `APPROVAL_PENDING` | `needs_me` | `connector_action` | `PENDING` 이고 `expires_at` 전 | 늘 | 승인, 거절, 만료 | `connector_action:<공개 식별자>` | `status` | `CONTROL_PLANE` |
| `MEMORY_PROPOSED` | `needs_me` | 주인의 `USER` Memory | `PROPOSED` | 아니다 | 받아들임, 거절 | `memory:<번호>` | `revision` | `MODEL_INFERRED` |
| `FOLLOW_UP_PROPOSED` | `needs_me` | `follow_up` | `PROPOSED` | 아니다 | 받아들임, 거절 | `follow_up:<공개 식별자>` | `updated_at` | `MODEL_INFERRED` |
| `FOLLOW_UP_OPEN` | `needs_me` | `follow_up` | `OPEN` | 기한이 `due-soon` 안이거나 지났다. 또는 연결한 대화에 새 답이 왔다 | 끝냄, 그만둠 | `follow_up:<공개 식별자>` | `updated_at` 과 연결한 대화의 마지막 메시지 번호 | `USER_CONFIRMED` |
| `DELEGATION_RUNNING` | `delegated` | 주인의 위임 실행. `delegation_key` 가 있다 | `RUNNING` | 시작한 지 `long-running-after` 를 넘었다 | 끝남 | `execution:<번호>` | `status` | `CONTROL_PLANE` |
| `DELEGATION_FINISHED` | `delegated` | 주인의 위임 실행 | 끝났고 `finished_at` 이 `delegated-window` 안 | 아니다 | 없다 | `execution:<번호>` | `status` | `CONTROL_PLANE` |
| `CONVERSATION_RECENT` | `continue` | 주인의 대화. `deleted_at` 이 비어 있다 | `updated_at` 순으로 `continue-count` 개 | 아니다 | 없다 | `conversation:<대화 공개 식별자>` | `updated_at` | `CONTROL_PLANE` |

「연결한 대화에 새 답이 왔다」 는 그 대화의 마지막 `ASSISTANT` 나 `SYSTEM` 메시지가 할 일의 `accepted_at` 과 그 항목의 마지막 `OPENED` 사건보다 뒤라는 뜻이다.

`MEMORY_PROPOSED` 는 기억 메뉴의 제안 건수에 이미 센다. 지금 화면의 건수에는 세지 않는다.

`DELIVERY_FAILED` 는 #162 가 main 에 들어온 뒤에 더한다. 그 상태를 이 패키지가 저장하거나 고치지 않는다.

## 억제 신호

아래를 위에서부터 보고 하나라도 맞으면 `SUPPRESSED` 다.

| 순서 | 신호 | 맞는 경우 |
| --- | --- | --- |
| 1 | `HIDDEN` | 사용자가 같은 `itemKey` 와 같은 `stateKey` 를 숨겼다 |
| 2 | `SNOOZED` | 사용자가 미룬 기한이 아직 지나지 않았다 |
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
| `continue-count` | 5 | 이어서 하기에 보이는 대화 수 |
| `max-items-per-card` | 10 | 카드 하나의 항목 상한. 넘으면 `moreCount` 로 센다 |
| `snooze-max` | 8일 | 미루기 기한의 상한 |
| `event-retention` | 90일 | 지표 사건을 남기는 기간 |

## 「왜 보였는가」

항목마다 아래 칸을 낸다. 화면은 이 코드를 정해진 문구로 그린다([`../frontend/now.md`](../frontend/now.md) 의 「이유 문구」).

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
| `LINKED_UPDATE` | 할 일에 연결한 대화에 새 답이 왔다 |
| `WAITING` | 할 일이 기다리는 중이다 |
| `LONG_RUNNING` | 맡긴 일이 오래 돌고 있다 |

`sources` 의 `ref` 는 문맥 묶음의 참조와 같은 형식이다([`context-bundle.md`](context-bundle.md) 의 「항목의 칸」).
**응답에 실행의 오류 코드, 모델, 금액을 싣지 않는다.** 일반 경로의 응답이라 역할과 상관없이 뺀다([ADR-063](../adr/ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md)).

## 카드의 단추와 승인 경계

먼저 알리기는 보이기만 한다. 단추는 이미 있는 경로로 보내고, 사용자가 누를 때만 돈다.

| 항목 | 단추 | 가는 곳 |
| --- | --- | --- |
| 실패한 turn | 대화 열기 | `/chat/{대화 공개 식별자}`. 다시 보낼지는 사람이 대화에서 정한다 |
| 결과 전달 실패 | 결과 다시 전하기 | #162 가 여는 사람 요청 경로. 원래 자식 작업이나 커넥터 쓰기를 다시 실행하지 않는다 |
| 승인 대기 | 대화에서 보기 | 그 대화의 승인 카드. 승인과 거절은 기존 `POST /api/v1/connector-actions/{actionId}/approve`, `.../reject` 다 |
| Memory 제안 | 기억에서 보기 | `/memory` |
| 할 일 제안 | 받아들이기, 거절 | [`follow-up.md`](follow-up.md) 의 API |
| 열린 할 일 | 끝냄, 그만둠, 고치기 | [`follow-up.md`](follow-up.md) 의 API |
| 맡긴 일 | 작업 과정 보기 | `/executions/{번호}` |
| 이어서 하기 | 대화 열기 | `/chat/{대화 공개 식별자}` |

**판정이 시작하지 않는 것**: Hermes 실행, 커넥터 호출, Memory 쓰기, 할 일 만들기.
사용자의 확인 없이 무언가를 실행하는 proactive 동작은 이 문서의 범위 밖이다. 열려면 새 ADR 과 ADR-050 같은 승인 줄이 먼저 있어야 한다.

## API

모두 웹 JWT 로 부르고 요청자 자기 것만 읽고 쓴다.

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/attention` | 카드 넷과 항목, `nowCount`, `readAt`. 응답에 실린 `NOW` 와 `LATER` 항목마다 `SHOWN` 사건을 한 번 남긴다 |
| `GET /api/v1/attention/summary` | `{ "nowCount": 3 }`. 사이드바와 홈의 한 줄이 읽는다. 사건을 남기지 않는다 |
| `POST /api/v1/attention/hide` | 본문 `{ itemKey, stateKey }`. 그 상태가 바뀔 때까지 숨긴다 |
| `POST /api/v1/attention/snooze` | 본문 `{ itemKey, until }`. `until` 은 지금보다 뒤이고 `snooze-max` 안이어야 한다. 아니면 400 `VALIDATION_FAILED` |
| `POST /api/v1/attention/restore` | 본문 `{ itemKey }`. 숨기기와 미루기를 지운다 |
| `POST /api/v1/attention/events` | 본문 `{ itemKey, stateKey, type }`. `type` 은 `OPENED` 나 `ACTED` 다 |
| `GET /api/v1/admin/attention/metrics?days=30` | 관리자만. 아래 「지표」 를 `trigger` 별로 센다. 제목과 항목 열쇠를 내지 않는다 |

`itemKey` 에 `:` 와 UUID 가 들어 있어 경로 대신 본문으로 받는다.
`itemKey` 의 주인이 요청자가 아니면 숨기기와 미루기는 404 다. 남의 항목이 있는지 알리지 않는다.

응답의 모양은 아래와 같다.

```json
{
  "readAt": "2026-10-04T09:00:00Z",
  "nowCount": 2,
  "cards": [
    {
      "key": "failures",
      "status": "OK",
      "moreCount": 0,
      "items": [
        {
          "itemKey": "conversation:7b1e…",
          "stateKey": "3f9a…",
          "attention": "NOW",
          "title": "주간 장보기 목록 정리",
          "conversationId": "7b1e…",
          "agentName": "집안일 도우미",
          "at": "2026-10-03T13:10:00Z",
          "why": { "trigger": "EXECUTION_FAILED", "signals": ["NOT_RETRIED"], "confidence": "CONTROL_PLANE", "sources": [] }
        }
      ]
    }
  ]
}
```

`title` 은 대화 제목이나 할 일 제목이나 승인 줄의 동작 이름이다. 화면은 평문으로 그린다(ADR-009).

## 저장

표의 칸은 [`schema/attention.md`](schema/attention.md) 가 갖는다.

- `attention_control`: 사용자의 숨기기와 미루기. 한 사용자의 한 `itemKey` 에 한 줄이다
- `attention_event`: 지표 사건. 같은 사용자, `itemKey`, `stateKey`, `type` 은 한 줄만 남긴다

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

**아직 재지 않는 것**: 지금 화면을 본 뒤 같은 것을 찾으려고 대화나 검색으로 돌아간 비율이다. 화면 사이의 이동을 기록하는 길이 없다.
첫 반응 시간은 [`../model-tiers.md`](../model-tiers.md) 의 「첫 반응 시간」 이 갖는다.
