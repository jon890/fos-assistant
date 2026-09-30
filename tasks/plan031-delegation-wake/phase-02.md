# Phase 02. 위임 결과가 끝나면 부모 대화의 자동 turn 을 연다

**Execution profile**: deep

## 목표

대화 turn 이 직접 맡긴 위임 실행이 `SUCCEEDED` 나 `FAILED` 로 끝나면, 그 대화에 turn 이 돌고 있지 않을 때 자동 turn 을 하나 연다.
자동 turn 은 `SYSTEM` 알림 줄을 남기고, 전하지 않은 결과를 모두 모아 Hermes 입력으로 넣는다.

**범위 외**: 대화 단위 SSE 엔드포인트와 웹 화면(phase 03). 이 phase 는 사건을 `ConversationEventHub` 에 내는 데까지 한다.

## 컨텍스트

phase 01 이 만든 것을 쓴다: `agent_execution.result_delivered_at`, `conversation.auto_turn_count`, `MessageRole.SYSTEM`, `ChatMessage.fromSystem`.

지금 구조(경로는 `backend/src/main/java/com/bifos/assistant/` 기준):

- turn 은 `chat/application/ChatService.java` 의 `runTurn(CurrentUser, Routed, String, TurnIntent, Consumer<ChatEvent>, boolean streaming, TurnHandle)` 이 돌린다. `saveQuestion` 이 사용자 메시지를 저장하고, `begin` 이 실행 줄을 만들고, `submit` 과 `relay` 와 `awaitCompletion` 이 Hermes 를 부른다. 사건은 `ChatEvent` record 로 `onEvent` 에 흘린다.
- `route(CurrentUser, Long conversationId, String text, String agentCode, List<Long>)` 가 대화, 에이전트, 흐름을 정한다. 지운 에이전트는 `AGENT_NOT_FOUND`, 꺼진 에이전트는 `AGENT_DISABLED` 로 거절한다.
- `TurnIntent` 는 sealed interface 다(`Fresh`, `Regenerate`). `instructionFor` 가 turn 별 지시를 붙인다.
- 대화 잠금은 `chat/application/TurnCancellation.java` 의 메모리 맵이다. `open(userId, conversationId)` 가 이미 있으면 `CONVERSATION_BUSY` 를 던지고, `close(handle)` 가 푼다.
- 위임 실행은 `orchestration/application/AgentDelegationService.java` 의 `run()` 가상 스레드에서 끝난다. 끝을 아는 자리는 그 `finally` 블록이다. `orchestration` 과 `chat` 은 이미 서로를 import 한다.
- 기동 정리는 `usage/application/OrphanedExecutionSweeper.java` 가 `ApplicationReadyEvent` 에서 `RUNNING` 을 `FAILED`(`ORPHANED`)로 적는다.
- `CurrentUser` 는 `shared/auth/CurrentUser.java` 의 record `(id, email, displayName, groupId, role)` 이다. 요청 없이 turn 을 열 때는 대화 주인의 `AppUser` 로 만든다(`ControlPlaneJwtFilter` 가 만드는 방식과 같다).
- 위임 설정은 `orchestration/application/DelegationProperties.java` 와 `application.yml` 의 `assistant.delegation` 이다.

**근거 문서**: `docs/flow.md` 의 「위임 결과가 도착했을 때」 절(갈리는 지점 표 전부),
`docs/code-architecture.md` 의 「위임 결과로 부모 대화를 깨우기」 절,
`docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md`

## 의도 메모

- 깨울지는 `TurnCancellation.open` 을 잡을 수 있는지로 정한다. 잡지 못하면 그 turn 이 닫힐 때 다시 확인하므로 결과를 잃지 않는다. 따로 대기열 표를 두지 않는다. 대기열은 `result_delivered_at IS NULL` 인 끝난 위임 실행이다.
- `orchestration` 은 `DelegationFinished` 사건만 낸다. 위임 서비스가 `ChatService` 를 직접 부르지 않는다.
- 자동 turn 은 새 가상 스레드에서 돈다. 위임 스레드나 turn 을 닫는 스레드를 붙잡지 않는다.
- 결과를 `result_delivered_at` 으로 적는 시점은 자동 turn 의 실행 줄을 만든 뒤, Hermes 에 제출하기 전이다. 제출이 실패해도 다시 깨우지 않는다(같은 결과로 반복 실패하는 루프를 막는다). 실패한 turn 은 보통 turn 처럼 실행 기록에 남는다.
- 대화에 자동 turn 이 끝나면 turn 종료 리스너가 다시 부른다. 그 사이 새로 끝난 결과가 있으면 이어서 연다. 이것이 연쇄이므로 상한이 필요하다.

## 작업 항목

### 1. `orchestration/application/DelegationFinished.java` 와 `AgentDelegationService` — 사건

- record `DelegationFinished(Long conversationId, Long executionId)` 를 둔다.
- `AgentDelegationService.run()` 의 `finally` 에서 실행 줄이 있고(`executionId != null`) 그 실행의 대화가 있으면 `ApplicationEventPublisher.publishEvent(new DelegationFinished(...))` 를 부른다. 사건을 내다 예외가 나도 `finally` 의 다른 정리를 막지 않게 `try/catch` 로 감싸고 경고 로그만 남긴다.

### 2. `usage/infra/AgentExecutionRepository` — 깨울 대상 조회

- 한 대화에서 전하지 않은 결과를 오래된 순으로 읽는 메서드를 더한다. 조건:
  - `conversation_id = ?`
  - `delegation_key IS NOT NULL`
  - `status IN ('SUCCEEDED', 'FAILED')`
  - `result_delivered_at IS NULL`
  - 부모 실행이 대화 turn 의 뿌리다: 부모 줄의 `parent_execution_id IS NULL`. 손자 실행을 빼기 위한 조건이다
- 기동할 때 쓸, 위 조건(대화 조건 제외)을 만족하는 줄이 있는 대화 번호 목록을 읽는 메서드를 더한다.

### 3. `chat/application/TurnIntent.java` — `DelegationResults`

- `record DelegationResults(List<Long> executionIds, String notice) implements TurnIntent` 를 더한다. `notice` 는 `SYSTEM` 줄 본문이다.
- `instructionFor` 가 이 intent 에 지시 한 줄을 돌려준다: 「맡긴 일의 결과가 도착했다. 결과를 사용자에게 정리해 전하고, 이어서 할 일이 있으면 진행한다. 아직 끝나지 않은 맡긴 일은 기다리지 말고 답을 마친다.」

### 4. `chat/application/ChatService.java` — 자동 turn

- public 메서드 `ChatTurn runDelegationResults(CurrentUser owner, Long conversationId, List<AgentExecution> results, TurnCancellation.TurnHandle handle, Consumer<ChatEvent> onEvent)` 를 더한다.
  - `route(owner, conversationId, <입력 글>, null, List.of())` 로 대화와 에이전트를 정한다. 흐름(`routed.flow() != null`) 대화면 열지 않고 돌아간다. 흐름 대화는 위임을 쓰지 않는다.
  - `runTurn(owner, routed, <입력 글>, new TurnIntent.DelegationResults(ids, notice), onEvent, true, handle)` 로 돌린다. `handle` 은 깨우기 서비스가 이미 연 것이다.
- `saveQuestion` 에 `DelegationResults` 분기를 더한다. 한 트랜잭션에서 `ChatMessage.fromSystem(conversation.id(), notice)` 를 저장하고, 대화의 `incrementAutoTurns()` 를 저장한다. 제목은 채우지 않는다. 저장한 `SYSTEM` 메시지로 `ChatEvent` 를 하나 흘린다(아래 7번의 `system` 사건).
- `begin` 으로 실행 줄을 만든 직후, 제출 전에 `results` 의 각 실행에 `markResultDelivered(now)` 를 적는다.
- 입력 글 형식(Hermes 입력). 결과마다 아래 블록을 잇는다. `output_text` 가 없으면 그 줄을 뺀다.

  ```text
  맡긴 일의 결과가 도착했다.

  [에이전트: <에이전트 이름>, 실행 번호: <id>, 상태: SUCCEEDED]
  <output_text>

  [에이전트: <에이전트 이름>, 실행 번호: <id>, 상태: FAILED, 오류: <error_code>]
  ```

- `notice` 형식: 결과가 하나면 「<에이전트 이름> 에이전트의 결과가 도착했어요」, 여럿이면 「<첫 에이전트 이름> 외 N개 에이전트의 결과가 도착했어요」. 이름은 실행 줄의 에이전트에서 읽는다.
- 다시 생성: `regenerate` 는 마지막 `USER` 메시지를 찾는다. 자동 turn 의 답 앞 줄이 `SYSTEM` 이므로 `MESSAGE_NOT_LATEST` 로 거절되는지 확인하고, 아니면 그렇게 되게 한다.

### 5. `chat/application/DelegationWakeService.java` — 깨우기 판정

- `@EventListener` 로 `DelegationFinished` 를 받아 `tryWake(conversationId)` 를 부른다.
- `TurnCancellation` 에 turn 종료 리스너를 등록할 수 있게 한다(`addCloseListener(Consumer<Long> conversationId)`). `close` 가 맵에서 뺀 뒤 리스너를 부른다. 리스너 예외는 로그만 남긴다. 깨우기 서비스는 이 리스너로 `tryWake` 를 부른다.
- `tryWake(Long conversationId)`:
  1. 전하지 않은 결과를 읽는다. 없으면 끝.
  2. 대화를 읽는다. 지워졌으면 끝. 에이전트가 지워졌거나 꺼졌으면 끝(결과는 그대로 남는다).
  3. `auto_turn_count` 가 `wake-max-auto-turns` 이상이면, 이 대화에 한도 알림을 이미 남기지 않았을 때만 `SYSTEM` 줄 「자동으로 이어 가는 횟수를 넘었어요. 이어서 하려면 메시지를 보내 주세요」 를 저장하고 `system` 사건을 낸 뒤 끝. 이미 남겼는지는 마지막 메시지가 그 알림인지로 본다.
  4. `turns.open(ownerId, conversationId)` 를 시도한다. `CONVERSATION_BUSY` 면 끝(그 turn 이 닫히며 다시 부른다).
  5. 잡았으면 새 가상 스레드에서 `chatService.runDelegationResults(owner, conversationId, results, handle, hub::publish(conversationId))` 를 돌리고, 끝나면 `turns.close(handle)` 한다. 예외는 로그로 남긴다. `close` 가 종료 리스너를 다시 부르므로 쌓인 결과가 있으면 이어서 연다.
- `@EventListener(ApplicationReadyEvent.class)` 로 기동 때 한 번, 전하지 않은 결과가 있는 대화마다 `tryWake` 를 부른다. `OrphanedExecutionSweeper` 가 먼저 끝나야 하므로 `@Order` 로 뒤에 둔다.
- 설정 `assistant.delegation.wake-max-auto-turns`(기본 10)를 `DelegationProperties` 와 `application.yml` 에 더한다. 주석은 한국어 한 줄로 단다.

### 6. `chat/application/ConversationEventHub.java` — 사건 전달

- 대화 번호마다 구독자(`Consumer<ChatEvent>`) 목록을 갖는다. `subscribe(conversationId, consumer)` 는 해제용 `Runnable` 을 돌려준다. `publish(conversationId, event)` 는 모든 구독자에게 보내고, 보내다 실패한 구독자는 뺀다.
- 동시성: `ConcurrentHashMap<Long, CopyOnWriteArrayList<...>>`.
- 이 phase 에서는 자동 turn 의 사건만 publish 한다. 사용자 turn 은 지금처럼 보낸 창에만 간다.

### 7. `chat/application/ChatEvent.java` — `system`

- `type` 이 `system` 인 사건을 더한다. 칸: `conversationId`(publicId), `messageId`, `content`. 기존 팩토리 모양을 따른다.

### 8. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java`(신규). 가짜 Hermes 는 기존 대화 테스트가 쓰는 방식을 따른다(`grep -rln "HermesRunsClient" backend/src/test` 로 찾는다).
  - 끝난 위임 결과가 있고 turn 이 없으면 자동 turn 이 돌아 `SYSTEM` 줄과 `ASSISTANT` 답이 저장되고, 결과의 `result_delivered_at` 이 채워진다
  - turn 이 돌고 있으면 열지 않고, 그 turn 을 닫으면 열린다
  - 결과 둘이 쌓여 있으면 자동 turn 하나에 둘 다 들어간다(Hermes 입력에 두 실행 번호가 있다)
  - `CANCELLED` 결과와 손자 실행의 결과는 깨우지 않는다
  - `auto_turn_count` 가 10 이면 열지 않고 한도 알림 줄을 한 번만 남긴다
  - 에이전트가 꺼졌으면 열지 않는다
- `test/e2e/scenarios/delegation-wake.ts`(신규). 기존 `test/e2e/scenarios/delegation.ts` 의 준비를 따른다. 부모가 `agent_delegate` 만 부르고 답을 마치면, 자식이 끝난 뒤 그 대화의 메시지 목록에 `SYSTEM` 줄과 두 번째 답이 생긴다. `test/e2e/run.ts` 가 시나리오를 차례로 부르는 방식에 등록한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
grep -rn "DelegationFinished" backend/src/main/java | wc -l
```

마지막 `grep` 은 3 이상(정의, 발행, 수신)이어야 한다.
머지 전에는 저장소 root 에서 `scripts/check-local.sh` 가 모두 통과해야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationFinished.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationEventHub.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 신규 |
| `test/e2e/scenarios/delegation-wake.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
