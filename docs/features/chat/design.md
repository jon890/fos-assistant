# 답이 도는 중에 보낸 글을 도는 실행에 끼워 넣는다(steer) 기술 설계

스펙: [`spec.md`](spec.md)(확인 2026-11-16). 결정과 근거는 [ADR-20261116 / run-steer](../../adr/ADR-20261116-run-steer.md) 가 갖는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `POST .../pending` 이 대기 줄에 글을 더한 뒤 `SteerService` 가 끼워 넣을 수 있는지 보고 Hermes `steer` 를 부른다. 받으면 대기 글을 빼고 `chat_message` 의 `USER` 행(steer 칸이 찬 행)을 한 트랜잭션에 저장하고 대화 단위 SSE 로 `steer` 사건을 낸다 |
| REQ2 | Hermes 가 409·400·500 으로 답하면 대기 글의 「끼우는 중」 표시만 지우고 `tryNext` 를 부른다. 시간 초과·연결 실패면 `UNCONFIRMED` 말풍선을 만들고 그 글을 말풍선 번호가 붙은 대기 글로 바꿔 줄을 멈춘다 |
| REQ3 | `HermesRunResult` 가 `pending_steer` 를 읽는다. turn 이 끝나는 자리(`ChatTurnRunner.runTurn` 의 바깥 `finally`, 예외 경로 포함)가 잠금을 풀기 전에 `TurnSteerLedger.settle` 을 불러 돌아온 글을 `CARRIED_OVER` 로 바꾸고, 말풍선 번호가 붙은 대기 글로 줄 앞에 되돌린다. 닫기 리스너의 `tryNext` 가 바로 보낸다 |
| REQ4 | 중지, 결과 조회 실패(Hermes 404, 시간 초과, 연결 실패), 그 밖의 예외로 끝나면 `settle` 이 그 실행의 `DELIVERED` 말풍선을 모두 `UNCONFIRMED` 로 바꾸고 대기 줄 앞에 되돌린 뒤 멈춘다. 상태 변경, 되돌리기, 멈춤은 한 트랜잭션이다 |
| REQ5 | 받은 순간 그 turn 에 Hermes 안 하위 에이전트가 돌면(`subagent.start` 뒤 `subagent.complete` 전) `SUBAGENT`, 그렇지 않고 그 대화에 `agent_delegate` 로 맡긴 실행이 돌면 `DELEGATION` 을 말풍선 행에 적는다. 화면이 종류에 맞는 안내 줄을 그린다 |
| REQ6 | 화면은 입력창에 쓴 글만 보낼 때 `steer: true` 를 싣는다(대기 경로 두 곳 모두). 서버는 그 표시가 있고, 설정이 켜져 있고, 스킬 커맨드가 아니고, 대기 줄이 비어 있고, 그 대화의 turn 이 끼울 수 있는 종류이고 루트 run 이 있을 때만 끼운다. run 과 turn 종류는 대화 번호로 `TurnCancellation` 의 handle 에서 찾는다 |
| REQ7 | 다시 생성이 고른 질문 행에 steer 칸이 차 있으면 `MESSAGE_NOT_LATEST` 로 거절한다. 이력 응답이 말풍선에 steer 칸을 실어 화면이 그 답의 다시 생성 단추를 숨긴다 |
| REQ8 | `assistant.chat.steer.enabled`(기본 `false`)가 꺼져 있으면 `SteerService` 가 Hermes 를 부르지 않고 지금 경로만 탄다 |
| REQ9 | `HermesRunsClient.steer` 와 가짜 Hermes(`StubHermesRunsClient`, E2E 의 `test/e2e/fake-hermes`)의 steer 경로·`pending_steer`. `hermes-contract.md` 의 steer 절과 ADR-048 을 고친다 |
| REQ10 | 코드가 아니라 배포 뒤 절차다. 설계는 설정 하나로 켜고 끄게 해 둔다. 확인 결과는 운영 저장소가 갖는다 |

## 책임과 경계

새 모듈을 만들지 않는다. 대기열(ADR-048)의 자리를 그대로 쓴다.

| 자리 | 맡는 것 |
| --- | --- |
| `chat/nextturn/application/SteerService`(새) | 끼울지 판정, Hermes `steer` 호출, 말풍선 상태와 대기 줄의 모든 steer 변경(받음, 거절, 모름, `settle`, 다음 turn 에서 `CARRIED_OVER`, 기동 정리). steer 상태 규칙의 주인은 여기 하나다. 다음 turn 을 여는 것은 여전히 `NextTurnDispatcher.tryNext` 하나다 |
| `PendingMessageService.enqueue` | 지금처럼 저장한 뒤, `tryNext` 전에 `SteerService.trySteer` 를 부른다 |
| `PendingMessageStore`·`PendingMessages` | 대기 글에 두 칸을 더한다. 「끼우는 중」 표시와 이미 보인 말풍선 번호. 바꾸기는 지금처럼 대화 줄을 잠근 `change` 하나로 한다 |
| `TurnCancellation`·`TurnHandle` | 루트 run(`apiBaseUrl`, profile, run 번호), turn 종류, 도는 하위 에이전트 수, 「정리됨」 표시를 더한다. steer 호출과 turn 의 `settle` 이 같은 handle 의 잠금을 잡아 차례로 돈다. `rekey` 가 비우는 `runs` 와 따로 둔다 |
| `ChatTurnRunner` | `runTurn` 의 바깥 `finally` 에서 잠금을 풀기 전에 포트의 `settle` 을 부르고 끝난 방식과 결과를 넘긴다. `ChatTurnStopper` 는 바꾸지 않는다 |
| `RestartReconciler` | 기동 때 끝난 run 을 물어 얻은 결과를 같은 `settle` 에 넘긴다. 그 앞에서 남은 「끼우는 중」 을 정리한다 |
| `ChatTurnRouting.saveQuestion` | 대기 글로 여는 turn 에서 말풍선 번호가 붙은 글은 새 행을 만들지 않고 같은 트랜잭션에서 포트로 그 행을 `CARRIED_OVER` 로 바꾼다 |
| `HermesRunsClient`·`HttpHermesRunsClient` | `steer(apiBaseUrl, profile, runId, text)` 와 결과의 `pendingSteer`. steer 는 읽기 제한 시간이 짧은 요청 factory 를 따로 둔 `RestClient` 로 보낸다(지금 클라이언트는 `readTimeout` 하나를 쓴다) |
| 화면 `use-conversation-queue.ts`·`use-conversation-events.ts`·`message-bubble.tsx` | `steer: true` 싣기, `steer` 사건으로 말풍선 넣기와 상태 바꾸기, 답 위 자리, 안내 줄, 다시 생성 단추 숨기기 |

의존 방향은 `SubFeatureOrder`(`turn` < `nextturn` < `recovery`)를 따른다. `nextturn` 과 `recovery` 는 `turn` 을 부를 수 있고 `turn` 은 그 둘을 부르지 못한다.
`turn` 쪽 `ChatTurnRunner` 와 `ChatTurnRouting` 이 steer 상태를 바꿔야 하므로 `turn/application/port` 에 `TurnSteerLedger` 포트를 두고 `nextturn` 의 `SteerService` 가 구현한다. `RestartReconciler` 는 `nextturn` 을 직접 부른다.

## 인터페이스와 데이터

### API

`POST /api/v1/chat/conversations/{id}/pending` 본문에 `steer`(불리언, 없으면 `false`)를 더한다. 응답은 지금처럼 대기 줄이다. 끼워 넣었으면 그 글은 대기 줄에 없다.
대기 줄 응답의 글에 `messageId`(말풍선 번호가 붙은 글이면 그 번호, 아니면 없음)와 `steering`(끼우는 중)을 더한다.
화면은 `held` 가 거짓일 때 `messageId` 가 있는 글을 대기 칸에 그리지 않는다. 이미 말풍선으로 보이고 곧 다음 turn 으로 가기 때문이다. 멈춘 줄이면 그린다(스펙 REQ4).

이력의 메시지에 `steer` 칸을 더한다. `{ "state": "DELIVERED|CARRIED_OVER|UNCONFIRMED", "child": "SUBAGENT|DELEGATION"|null }` 이고 보통 질문이면 없다.
새 SSE 사건 `steer` 는 `{ conversationId, messageId, text, state, child, executionId }` 이다. 말풍선이 생길 때와 상태가 바뀔 때 낸다.

| `child` | 안내 줄 |
| --- | --- |
| `SUBAGENT` | 도우미가 하던 일은 바꾸지 못하고, 도우미가 끝난 뒤 반영돼요 |
| `DELEGATION` | 맡긴 도우미가 하던 일은 바꾸지 못해요 |

| 상태 | 화면 글 |
| --- | --- |
| `DELIVERED` | 전달됨 |
| `CARRIED_OVER` | 다음 차례로 넘어감 |
| `UNCONFIRMED` | 전달됐는지 확인 못 함 |

### 끼울 수 있는 turn

| turn 종류(`TurnIntent`) | 끼우나 |
| --- | --- |
| `Fresh`(사람이 보낸 질문, 대기 글로 연 turn) | 끼운다 |
| `Regenerate` | 끼운다 |
| `DelegationResults`(위임 결과 자동 turn, 다시 전하기) | 끼운다 |
| `Mention`(`@` 맡김) | 끼우지 않는다. 맡은 에이전트가 새 session 에서 돈다(ADR-20261112 / mention-delegation) |
| `Scheduled`, `ProactiveCheck` | 끼우지 않는다. 사람이 보낸 turn 이 아니고, 살펴보기는 결과 블록 계약이 있다 |

### Hermes

`POST /v1/runs/{run_id}/steer` 본문 `{"input": "<글>"}`. 루트 run 의 `apiBaseUrl` 과 profile key 로 보낸다. 요청 제한 시간은 `assistant.chat.steer.timeout`(기본 5초)이다.
`HermesRunResult` 에 `pendingSteer`(문자열, 없으면 null)를 더하고 `GET /v1/runs/{id}` 의 `pending_steer` 를 읽는다. 끝난 결과는 이미 이 조회로 얻는다(`HttpHermesRunsClient.toResult`).

| Hermes 응답 | 판정 |
| --- | --- |
| 200 `accepted: true` | 받음 |
| 409, 400, 500, 그 밖의 4xx·5xx 응답 | 거절. 대기열로 |
| 시간 초과, 연결 실패, 응답을 읽지 못함 | 모름 |

### 데이터

`chat_message` 에 칸 셋을 더한다. 테이블은 늘리지 않는다.

| 칸 | 뜻 |
| --- | --- |
| `steer_state` `VARCHAR(20) NULL` | 위 세 상태. 비면 steer 말풍선이 아니다 |
| `steer_execution_id` `BIGINT NULL` | 끼워 넣은 turn 의 루트 실행. 외래 키를 두지 않는다(`execution_id` 와 같은 까닭) |
| `steer_child` `VARCHAR(20) NULL` | 받은 순간 돈 자식의 종류. `SUBAGENT`, `DELEGATION`, 비면 없음 |

`execution_id` 는 `USER` 행에서 비어 있다는 지금 규칙(`backend/docs/data-schema.md`)을 지킨다. 답 줄로 읽는 질의가 `execution_id` 로 답을 고르기 때문이다.
대기 줄 JSON 의 글에 `messageId`(숫자, 없을 수 있음)와 `steering`(불리언, 없으면 거짓)을 더하고 버전을 2로 올린다(`PendingMessageStore` 의 「칸을 바꾸려면 버전을 올린다」). 새 칸이 찬 글이 하나도 없으면 지금처럼 버전 1 로 쓴다. 그래서 설정이 꺼져 있는 동안 저장되는 값은 앞 이미지가 읽을 수 있다. 버전 2 를 모르는 앞 이미지는 그 대화의 대기 줄을 읽지 못한다고 보고 보내지 않는다(지금 `decode` 규칙). 버전 2 를 지금 규칙과 함께 `backend/docs/data-schema.md` 와 ADR-048 「저장 자리 개정」 에 적는다.
상한(5개, 8000자)은 말풍선 번호가 없는 글에만 센다. 말풍선 번호가 붙은 글은 이미 받아 말풍선으로 보인 글이라 되돌릴 때 상한으로 막지 않고, 다음 turn 에서 새 사용자 행이 되지도 않는다. 그 turn 의 Hermes 입력은 8000자를 넘을 수 있다.
돌아온 글로 연 turn 의 `question_message_id` 는 새 행이 있으면 그 행, 없으면 첫 말풍선 행이다.

### 끼우기 흐름

```text
enqueue(steer=true)
  change: 더하기. 줄이 비어 있었고 멈추지 않았고 끼울 수 있으면 그 글에 steering=true
  trySteer (잠금 밖, handle 의 steer 잠금 안)
    루트 run 을 다시 확인. 없으면 steering 지우고 끝
    Hermes steer
      받음  → 한 트랜잭션: 대기 글 빼기 + USER 행(DELIVERED, steer_execution_id, child) 저장 → steer 사건, pending 사건
      거절  → steering 지우기 → pending 사건
      모름  → 한 트랜잭션: 대기 글 빼기 + USER 행(UNCONFIRMED) + 말풍선 번호 붙은 대기 글 + held=true → steer 사건, pending 사건
  tryNext
```

`trySteer` 는 handle 의 steer 잠금 안에서 「정리됨」 표시를 본다. 이미 정리된 turn 이면 Hermes 를 부르지 않고 거절로 처리한다. 그래서 `settle` 뒤 잠금을 풀기 전에 들어온 글이 정리되지 않은 말풍선이 되지 않는다.

`steering` 이 찬 글이 있는 줄에서 `NextTurnDispatcher.tryPending` 은 「이 대화의 다음 차례를 차지했다」(참)로 답하고 아무것도 열지 않는다. 그래서 그 사이 turn 이 닫혀도 위임 결과 자동 turn 이 사용자 글을 앞지르지 않는다(ADR-048 의 「사용자의 말이 먼저」). 응답 뒤 `trySteer` 가 `tryNext` 를 부르므로 보낼 계기가 남는다. `steering` 글의 취소는 `CONVERSATION_BUSY` 로 거절한다(스펙 DECISION17).
서버가 그 사이 죽으면 `steering` 이 남는다. `RestartReconciler` 의 기동 정리(`@Order(0)`) 안에서, 잠금을 다시 잡기 전에 남은 `steering` 을 모름과 같이 처리한다. 받았는지 알 수 없고, 정리 전에 닫기 리스너가 먼저 돌면 위 판정이 그 줄을 붙잡아 둔다.

### turn 이 끝날 때(`settle`)

`ChatTurnRunner.runTurn` 의 바깥 `finally` 가, 잠금을 풀기 전에 `settle(handle, outcome, pendingSteer)` 를 부른다. 결과를 판정한 경로, 중지, 예외 경로가 모두 지난다. handle 의 steer 잠금을 잡으므로 도는 steer 요청이 먼저 끝나고, 같은 잠금 안에서 「정리됨」 을 세운다.

| 끝난 방식 | 그 실행의 `DELIVERED` 말풍선 |
| --- | --- |
| 완료·실패, `pendingSteer` 없음 | 그대로 |
| 완료·실패, `pendingSteer` 있음 | 받은 순서로 이은 글(줄바꿈)의 뒤쪽이 `pendingSteer` 와 같은 묶음만 `CARRIED_OVER`. 맞는 묶음이 없으면 모두. 그 글들을 말풍선 번호를 붙여 대기 줄 앞에 넣는다 |
| 중지, 결과 조회 실패(Hermes 404, 시간 초과, 연결 실패), 그 밖의 예외 | 모두 `UNCONFIRMED`. 대기 줄 앞에 넣고 멈춘다 |

`settle` 의 말풍선 상태 변경, 대기 줄 앞에 넣기, 멈춤은 `SteerService` 의 한 트랜잭션이다. 말풍선이 없는 turn 은 아무것도 하지 않는다. 실패하면 삼키지 않고 경고를 남긴 뒤 그 대화를 「정리 못 함」 으로 적어 둔다. 기동 정리와 그 대화의 다음 `tryNext` 가 남은 `DELIVERED` 말풍선(이미 끝난 실행의 것)을 찾아 「결과 모름」 으로 다시 정리한다. 말풍선 행이 남아 있어 다시 찾을 수 있다.
기동 정리(`RestartReconciler.settleByAsking`)는 끝난 run 을 물어 얻은 결과를 그대로 넘긴다. `pending_steer` 가 있으면 REQ3 대로, 결과가 없거나 run 이 없으면 「결과 모름」 이다.

### 대기 글로 여는 turn

`ChatTurnRouting.saveQuestion` 이 대기 줄에서 글을 빼는 트랜잭션에서, 말풍선 번호가 붙은 글은 그 행을 `CARRIED_OVER` 로 바꾸고 `steer` 사건을 낸다. 나머지 글만 지금처럼 합쳐 새 `USER` 행이 된다. Hermes 입력은 모든 글을 줄 순서대로 빈 줄로 잇는다.
멈춘 줄에서 사용자가 말풍선 번호가 붙은 글을 취소하면 그 글만 빠지고 말풍선은 `UNCONFIRMED` 로 남는다(지금 취소 경로 그대로).

### 화면

대기 경로는 둘이다. 답이 도는 중의 `submit` 과, 보통 보내기가 `CONVERSATION_BUSY` 를 받아 대기 줄로 다시 넣는 경로(`use-conversation-send.ts`)다. `queueMessage` 가 출처(입력창, 카드)를 받아 입력창에서 쓴 글만 `steer: true` 를 싣는다. 사진이 있거나 맡김을 고른 글은 대기 경로로 오지 않는다. 스킬 커맨드는 서버가 `SkillCommandCatalog` 의 해석을 재사용해 거른다.
`steer` 사건을 받으면 그 번호의 말풍선을 넣거나 상태를 바꾼다. 넣는 자리는 그 실행의 답(도는 답이면 그 자리표시) 바로 위다. 이력을 다시 읽으면 행 번호 순서로 같은 자리에 온다(말풍선 행은 답 행보다 먼저 저장된다).
돌아온 글로 연 turn 의 답은 앞 turn 의 답 뒤에 이어진다. 그 말풍선은 앞 답 위에 「다음 차례로 넘어감」 으로 남는다.

## 실패와 일관성

- **글을 잃지 않는다.** 대기 줄에 먼저 저장하고, 대기 글을 빼는 것과 말풍선 저장은 한 트랜잭션이다. Hermes 호출은 트랜잭션 밖이다(ADR-048 의 「저장한 뒤 다음을 정한다」).
- **steer 와 turn 끝의 경쟁**은 handle 의 steer 잠금과 「정리됨」 표시로 줄 세운다. `settle` 은 도는 steer 요청이 끝난 뒤 돌아 방금 받은 말풍선도 맞추고, 그 뒤의 steer 는 거절로 처리한다. steer 요청은 제한 시간이 있어 turn 끝이 그 이상 늦지 않는다.
- **`settle` 실패**는 말풍선이 `DELIVERED` 로 남는 것으로 드러난다. 끝난 실행의 `DELIVERED` 말풍선을 다시 찾아 정리하므로 글이 사라지지 않는다.
- **run 이 끝난 뒤 온 steer** 는 Hermes 가 409 로 거절해 대기열로 간다. Hermes 가 마지막 정리에서 슬롯을 비운 뒤 상태를 바꾸기 전 짧은 틈에 받은 글은 `pending_steer` 에도 없어 사라질 수 있다. 소스로 본 틈이며 운영 확인 3·4 로 본다.
- **두 창**은 대화 줄 잠금으로 더하기를 줄 세운다. 줄이 비어 있을 때만 끼우므로 두 번째 글은 첫 글이 `steering` 인 동안 대기 칸에 들어가 순서를 지킨다.
- **설정을 끄면** `trySteer` 가 곧바로 돌아온다. 이미 남은 `steering`, 말풍선 번호가 붙은 글은 지금 경로로 마저 간다.
- **되돌리기와 배포 순서**: 마이그레이션은 칸만 더해 앞 이미지와 함께 돈다. 설정을 켜기 전에는 대기 줄이 버전 1 로만 저장되므로 끈 채 배포하면 되돌릴 수 있다. 켠 뒤 되돌리면 버전 2 로 남은 대기 줄은 앞 이미지가 보내지 않고 남겨 둔다(지우지 않는다). 다시 이 이미지로 올리면 이어서 간다.

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1, REQ2 | `SteerService` 단위 시험(받음·거절·모름·설정 꺼짐·줄이 비지 않음·스킬 커맨드) + `StubHermesRunsClient` 로 대기 줄과 말풍선 행을 보는 통합 시험 |
| REQ3, REQ4 | `ChatTurnRunner` 를 지나는 통합 시험. 가짜가 `pending_steer` 를 돌려주는 경우, 중지, 404. 대기 줄 앞 자리, 멈춤, 상태 |
| REQ5 | 하위 에이전트 사건과 도는 위임 실행으로 `child` 시험 |
| REQ6 | 컨트롤러 시험(남의 대화 404, `steer` 없으면 끼우지 않음)과 화면 시험(카드 답·사진·맡김은 `steer` 없이) |
| REQ7 | 다시 생성 거절 시험과 화면에서 단추가 없는 것 |
| REQ8 | 기존 대기열·중지 시험이 그대로 통과 |
| REQ9 | `HttpHermesRunsClient` 의 steer·`pending_steer` 읽기 시험, 가짜 Hermes 의 steer 경로 E2E 한 흐름 |
| REQ10 | 배포 뒤 운영 확인(코디네이터). 코드 시험 없음 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 말풍선을 어디에 저장하나 | `chat_message` 의 `USER` 행에 steer 칸 셋 | 작성자 | 이력·암호화·지우기가 그대로 따라온다. 테이블을 늘리지 않는다(사용자 결정 2026-11-01). JSON 한 칸은 실행으로 말풍선을 찾는 질의가 어렵다 |
| DECISION2 | 돌아온 글을 어떻게 다음 turn 으로 보내나 | 말풍선 번호를 붙인 대기 글로 줄 앞에 되돌린다 | 작성자 | 다음 turn 을 여는 자리, 재시작 확인, 멈춤, 합치기를 ADR-048 그대로 쓴다. 따로 「보낼 말풍선」 목록을 두면 다음 turn 을 정하는 자리가 둘이 된다 |
| DECISION3 | 끼울 run 을 어디서 찾나 | `TurnCancellation` 의 handle 에 둔 루트 run | 작성자 | 요청 본문이 run 을 정하지 못하게 한다(`AGENTS.md`). turn 잠금과 같은 메모리에 있어 서버 한 대 전제(ADR-048)와 같다 |
| DECISION4 | steer 와 turn 끝의 순서 | handle 마다 잠금 하나로 줄 세운다 | 작성자 | 받은 응답 전에 `settle` 이 돌면 방금 받은 글을 맞추지 못한다 |
| DECISION5 | 입력창 글인지 누가 아나 | 화면의 `steer` 표시 + 서버의 판정(설정, 스킬 커맨드, 빈 줄, run) | 작성자 | 카드 답과 입력창 글은 같은 경로로 온다. 표시는 범위를 좁힐 뿐이고 소유 판정은 지금 대기열과 같다 |
| DECISION6 | 다시 생성 거절 코드 | 지금 `MESSAGE_NOT_LATEST` | 작성자 | 화면이 단추를 숨겨 사람이 볼 일이 드물다. 새 오류 코드를 만들 까닭이 없다 |
| DECISION7 | 기능 설정 | `assistant.chat.steer.enabled`(기본 `false`), `assistant.chat.steer.timeout`(기본 5초) | 작성자 | 스펙 DECISION8. 서버 설정 하나로 끈다 |
| DECISION8 | 대기 줄 JSON 버전 | 새 칸이 찬 글이 있을 때만 버전 2 로 쓴다 | 작성자 | 칸 이름이 저장되므로 버전을 올린다는 지금 규칙을 지키고, 설정을 끈 동안은 앞 이미지와 호환된다 |
| DECISION9 | 되돌린 글과 상한 | 말풍선 번호가 붙은 글은 상한을 세지 않는다 | 작성자 | 이미 받아 보인 글을 상한 때문에 버리면 글을 잃는다. 보이지 않는 글 때문에 `PENDING_QUEUE_FULL` 을 받지 않는다 |
| DECISION10 | 끼울 수 있는 turn | `Fresh`, `Regenerate`, `DelegationResults` | 작성자 | 스펙 REQ1 이 꼽은 네 turn 이다. 맡김은 다른 에이전트의 새 session 이라 대화 에이전트에 닿지 않는다 |
| DECISION11 | 자식 안내 | Hermes 안 하위 에이전트와 맡긴 실행을 나눠 안내한다 | 작성자 | 맡긴 실행은 부모가 기다리지 않아(ADR-040) 글이 부모에 곧바로 들어간다. 「끝난 뒤 반영돼요」 는 하위 에이전트에만 맞다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION1, DECISION2, DECISION4, DECISION6, 이 설계 DECISION2 | architecture | [ADR-20261116 / run-steer](../../adr/ADR-20261116-run-steer.md). ADR-048 의 「적용 범위」 를 개정한다 | 이 설계와 [`chat.md`](../chat.md) 의 「대기열과 중지」 |
| 이 설계 DECISION1 | architecture | ADR 에 함께 적는다. 칸 정의는 `backend/docs/data-schema.md` | `backend/docs/data-schema.md` 의 `chat_message` |
