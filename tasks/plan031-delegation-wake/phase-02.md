# Phase 02. 위임 결과가 끝나면 부모 대화의 자동 turn 을 연다

**Execution profile**: deep

## 목표

대화 turn 이 직접 맡긴 위임 실행이 `SUCCEEDED` 나 `FAILED` 로 끝나면, 그 대화에 turn 이 돌고 있지 않을 때 자동 turn 을 하나 연다.
자동 turn 은 `SYSTEM` 알림 줄을 남기고, 전하지 않은 결과를 모두 모아 Hermes 입력으로 넣는다.
자동 turn 의 사건은 `ConversationEventHub` 로 낸다.

**범위 외**: 대화 단위 SSE 엔드포인트와 웹 화면(phase 03).

## 컨텍스트

phase 01 이 만든 것을 쓴다.
- `agent_execution.result_delivered_at` 과 `AgentExecutionRepository.markResultDelivered`
- `conversation.auto_turn_count` 와 `ConversationRepository.incrementAutoTurns`, `resetAutoTurns`
- `MessageRole.SYSTEM`, `ChatMessage.fromSystem`

지금 구조(경로는 `backend/src/main/java/com/bifos/assistant/` 기준):

- turn 은 `chat/application/ChatService.java` 의 `runTurn(CurrentUser, Routed, String, TurnIntent, Consumer<ChatEvent>, boolean streaming, TurnHandle existingHandle)` 이 돌린다.
  - `saveQuestion` 이 사용자 메시지를 저장하고, `begin` 이 실행 줄을 만들고, `submit` 과 `relay` 와 `awaitCompletion` 이 Hermes 를 부른다.
  - `runTurn` 은 `started` 까지만 낸다. `done`, `stopped` 는 부르는 쪽(`stream`, `regenerate`)이 `ChatTurn` 을 보고 낸다(`ChatService.java` 의 `stream` 메서드 끝). 예외를 `error` 사건으로 바꾸는 것은 `chat/presentation/ChatEventStreams.java` 다.
- `route(CurrentUser, Long conversationId, String text, String agentCode, List<Long>)` 가 대화, 에이전트, 흐름을 정한다. 지운 에이전트는 `AGENT_NOT_FOUND`, 꺼진 에이전트는 `AGENT_DISABLED` 로 거절한다.
- 다시 생성은 `ChatService.regenerate` 이고, 앞 질문을 `previousQuestion(active, answer)` 가 뒤로 거슬러 가며 첫 `USER` 로 찾는다. 지금은 `SYSTEM` 을 건너뛴다.
- `TurnIntent` 는 sealed interface 다(`Fresh`, `Regenerate`). `instructionFor` 가 turn 별 지시를 붙인다.
- 대화 잠금은 `chat/application/TurnCancellation.java` 의 메모리 맵이다. `open(userId, conversationId)` 가 이미 있으면 `CONVERSATION_BUSY` 를 던지고, `close(handle)` 가 푼다.
- 위임 실행은 `orchestration/application/AgentDelegationService.java` 의 `run()` 가상 스레드에서 끝나고, 끝을 아는 자리는 그 `finally` 블록이다. 생성자로 의존을 받는다. `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java:72` 가 생성자를 직접 부른다.
- 기동 정리는 `usage/application/OrphanedExecutionSweeper.java` 의 `@EventListener(ApplicationReadyEvent.class)` 다. `@Order` 가 없다.
- `CurrentUser` 는 `shared/auth/CurrentUser.java` 의 record `(id, email, displayName, groupId, role)` 이다. 요청 없이 turn 을 열 때는 대화 주인의 `AppUser`(`user/infra/AppUserRepository`)로 만든다. `shared/auth/ControlPlaneJwtFilter.java:83` 과 같은 방식이다.
- **테스트 격리**: 모든 `@SpringBootTest` 가 같은 H2(`jdbc:h2:mem:assistant`, `DB_CLOSE_DELAY=-1`)와 대역 Hermes(`StubHermesRunsClient`)를 쓴다. `backend/src/test/resources/application-test.yml` 의 `starters.enabled: false` 주석이 같은 문제의 선례다. 위임을 끝까지 돌리는 기존 테스트(`orchestration/AgentDelegationServiceTest.java`, `mcp/McpAgentToolsTest.java`)에서 자동 turn 이 열리면 그 단언이 흔들린다.

**근거 문서**: `docs/flow.md` 의 「위임 결과가 도착했을 때」 절(갈리는 지점 표 전부),
`docs/code-architecture.md` 의 「위임 결과로 부모 대화를 깨우기」 절,
`docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md`

## 의도 메모

- 깨울지는 `TurnCancellation.open` 을 잡을 수 있는지로 정한다. 잡지 못하면 그 turn 이 닫힐 때 다시 확인한다. 대기열은 `result_delivered_at IS NULL` 인 끝난 위임 실행이다.
- **`SYSTEM` 줄 저장, `result_delivered_at` 기록, `auto_turn_count` 증가는 한 트랜잭션이다.** SYSTEM 줄이 곧 「전했다」 는 표시다. 그 뒤 turn 이 실패해도 같은 결과로 다시 깨우지 않는다(같은 실패를 되풀이하는 루프를 막는다).
- 잠금을 잡은 뒤 결과를 다시 읽는다. 잠금 밖에서 읽은 목록은 다른 자동 turn 이 이미 전했을 수 있다.
- 흐름 대화와 꺼지거나 지운 에이전트는 잠금을 잡기 전에 거른다. 잡은 뒤 아무것도 저장하지 않고 닫으면, 닫기 리스너가 곧바로 다시 불러 끝없이 돈다.
- `orchestration` 은 `DelegationFinished` 사건만 낸다. 위임 서비스가 `ChatService` 를 직접 부르지 않는다.
- 자동 turn 은 새 가상 스레드에서 돈다.

## 작업 항목

### 1. `chat/application/DelegationWakeProperties.java` — 설정

- `@ConfigurationProperties("assistant.delegation-wake")` record `DelegationWakeProperties(@DefaultValue("true") boolean enabled, @DefaultValue("10") int maxAutoTurns)`. `maxAutoTurns` 가 1 미만이면 기동을 멈춘다(`DelegationProperties` 의 `requirePositive` 방식).
- 설정 클래스 등록은 기존 `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties` 방식을 따른다(`grep -rn "DelegationProperties.class\|ConfigurationPropertiesScan" backend/src/main` 로 확인).
- `backend/src/main/resources/application.yml` 에 `assistant.delegation-wake.enabled: true`, `max-auto-turns: 10` 을 한국어 주석 한 줄씩과 함께 둔다.
- `backend/src/test/resources/application-test.yml` 에 `assistant.delegation-wake.enabled: false` 를 두고, 까닭을 `starters.enabled` 주석처럼 한 줄로 적는다.

### 2. `orchestration/application/DelegationFinished.java`, `AgentDelegationService.java` — 사건

- record `DelegationFinished(Long conversationId, Long executionId)`.
- `AgentDelegationService` 생성자에 `ApplicationEventPublisher` 를 더하고, `run()` 의 `finally` 에서 실행 줄이 있으면 사건을 낸다. 사건을 내다 예외가 나도 `finally` 의 다른 정리를 막지 않게 `try/catch` 로 감싸 경고 로그만 남긴다.
- `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java` 의 생성자 호출에 `event -> {}` 을 더한다.

### 3. `usage/infra/AgentExecutionRepository.java` — 깨울 대상 조회

- `List<AgentExecution> findUndeliveredResults(Long conversationId)`: 조건은 아래와 같고, 오래된 순으로 정렬한다.
  - `conversation_id = :conversationId`
  - `delegation_key is not null`
  - `status in (SUCCEEDED, FAILED)`
  - `result_delivered_at is null`
  - 부모 실행의 `parent_execution_id is null`. 손자 실행을 뺀다
- `List<Long> findConversationsWithUndeliveredResults()`: 위 조건에서 대화 조건을 빼고, 대화 번호를 중복 없이 읽는다.

### 4. `usage/application/OrphanedExecutionSweeper.java` — 순서

- `sweep()` 에 `@Order(0)` 을 둔다. 깨우기 서비스의 기동 훑기는 `@Order(10)` 이다.

### 5. `chat/application/TurnIntent.java` — `DelegationResults`

- `record DelegationResults(List<Long> executionIds, String notice) implements TurnIntent`.
- `instructionFor` 가 이 intent 에 지시를 돌려준다: 「맡긴 일의 결과가 도착했다. 결과를 사용자에게 정리해 전하고, 이어서 할 일이 있으면 진행한다. 아직 끝나지 않은 맡긴 일은 기다리지 말고 답을 마친다.」

### 6. `chat/application/ChatEvent.java` — `system`

- `type` 이 `system` 인 사건과 팩토리 `ChatEvent.system(String conversationPublicId, Long messageId, String content)` 를 더한다. `forViewer` 가 이 사건을 그대로 통과시키는지 확인한다.

### 7. `chat/application/ChatService.java` — 자동 turn 과 다시 생성

- public `void runDelegationResults(CurrentUser owner, Long conversationId, TurnCancellation.TurnHandle handle, Consumer<ChatEvent> onEvent)`
  1. `findUndeliveredResults(conversationId)` 를 **다시** 읽는다. 비었으면 아무것도 하지 않고 돌아간다.
  2. `route(owner, conversationId, input, null, List.of())` 로 대화와 에이전트를 정한다.
  3. `runTurn(owner, routed, input, new TurnIntent.DelegationResults(ids, notice), onEvent, true, handle)` 로 돌린다.
  4. 돌아온 `ChatTurn` 으로 `onEvent` 에 `done` 이나 `stopped` 를 낸다. `stream` 메서드 끝과 같은 방식이다.
- `saveQuestion` 에 `DelegationResults` 분기를 더한다. 한 트랜잭션에서 셋을 한다.
  - `ChatMessage.fromSystem(conversation.id(), notice)` 저장
  - intent 의 실행마다 `markResultDelivered(id, now)`
  - `conversations.incrementAutoTurns(conversation.id())`

  제목은 채우지 않는다. 트랜잭션이 커밋된 뒤 `onEvent.accept(ChatEvent.system(publicId, savedId, notice))` 를 낸다.
- Hermes 입력 형식. 결과마다 아래 블록을 잇는다. `output_text` 가 없으면 본문 줄을 뺀다.

  ```text
  맡긴 일의 결과가 도착했다.

  [에이전트: <에이전트 이름>, 실행 번호: <id>, 상태: SUCCEEDED]
  <output_text>

  [에이전트: <에이전트 이름>, 실행 번호: <id>, 상태: FAILED, 오류: <error_code>]
  ```

- `notice` 형식: 결과가 하나면 「<에이전트 이름> 에이전트의 결과가 도착했어요」, 여럿이면 「<첫 에이전트 이름> 외 N개 에이전트의 결과가 도착했어요」. 이름은 실행 줄의 에이전트에서 읽는다.
- `previousQuestion` 을 바꾼다. 답 바로 앞의 활성 메시지가 `SYSTEM` 이면 `null` 을 돌려준다. 그러면 `regenerate` 가 기존 규칙대로 거절한다. 거절 코드는 지금 앞 질문이 없을 때 쓰는 코드를 그대로 쓴다.

### 8. `chat/application/TurnCancellation.java` — 닫기 리스너

- `addCloseListener(Consumer<Long> listener)` 를 더한다. `close` 가 맵에서 뺀 뒤 대화 번호로 리스너를 부른다. 리스너 예외는 경고 로그만 남긴다.

### 9. `chat/application/ConversationEventHub.java`

- 대화 번호마다 구독자 목록을 갖는다(`ConcurrentHashMap<Long, CopyOnWriteArrayList<Consumer<ChatEvent>>>`).
- `Runnable subscribe(Long conversationId, Consumer<ChatEvent> consumer)` 는 해제용 `Runnable` 을 돌려준다.
- `void publish(Long conversationId, ChatEvent event)` 는 모든 구독자에게 보내고, 보내다 예외가 난 구독자는 뺀다.

### 10. `chat/application/DelegationWakeService.java` — 판정

`enabled` 가 거짓이면 아래 모두 아무것도 하지 않는다.

- 생성할 때 `turns.addCloseListener(this::tryWake)` 를 등록한다.
- `@EventListener` 로 `DelegationFinished` 를 받아 `tryWake(conversationId)` 를 부른다.
- `@EventListener(ApplicationReadyEvent.class) @Order(10)`: `findConversationsWithUndeliveredResults()` 의 대화마다 `tryWake` 를 부른다.
- `tryWake(Long conversationId)`
  1. `findUndeliveredResults(conversationId)` 가 비었으면 끝.
  2. 대화를 읽는다. 지워졌으면 끝. 에이전트가 지워졌거나 꺼졌으면 끝. 에이전트에 흐름이 있으면 끝(`FlowRegistry.find(agent.flow()) != null`). 결과는 그대로 남는다.
  3. `auto_turn_count >= maxAutoTurns` 이면 끝. 단, 대화의 마지막 메시지가 한도 알림이 아닐 때만 `SYSTEM` 줄 「자동으로 이어 가는 횟수를 넘었어요. 이어서 하려면 메시지를 보내 주세요」 를 저장하고 `hub.publish(system 사건)` 를 한다.
  4. `turns.open(ownerId, conversationId)` 를 시도한다. `CONVERSATION_BUSY` 면 끝.
  5. 잡았으면 새 가상 스레드에서 `chatService.runDelegationResults(owner, conversationId, handle, event -> hub.publish(conversationId, event))` 를 돈다. 예외가 나면 `ChatEvent.error(...)` 를 publish 하고 로그를 남긴다. `finally` 에서 `turns.close(handle)` 한다. `close` 가 리스너를 다시 부르므로 쌓인 결과가 있으면 이어서 연다.
  - 스레드를 띄우지 못하면 `turns.close(handle)` 하고 로그만 남긴다.

### 11. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java`(신규). `@SpringBootTest` 에 `properties = "assistant.delegation-wake.enabled=true"` 를 둔다. 대역 Hermes 는 기존 대화 테스트의 `StubHermesRunsClient` 사용법을 따른다. 위임 실행 줄은 리포지토리로 직접 저장해 만든다(부모는 turn 뿌리 실행, `delegation_key` 채움).
  - 끝난 결과가 있고 turn 이 없을 때 `DelegationFinished` 를 내면 자동 turn 이 돈다. `SYSTEM` 줄과 `ASSISTANT` 답이 저장되고, 결과의 `result_delivered_at` 이 채워지고, `auto_turn_count` 가 1 이다
  - 같은 대화에 turn 을 열어 둔 채 사건을 내면 열리지 않고, 그 turn 을 `close` 하면 열린다
  - 결과 둘이 쌓여 있으면 자동 turn 하나에 둘 다 들어간다. 대역 Hermes 가 받은 입력에 두 실행 번호가 있다
  - `CANCELLED` 결과와 손자 실행의 결과는 깨우지 않는다
  - `auto_turn_count` 가 10 이면 열지 않고 한도 알림 줄을 남긴다. 사건을 두 번 내도 알림 줄은 하나다
  - 에이전트가 꺼졌으면 열지 않고 SYSTEM 줄도 남기지 않는다
  - 기동 훑기: 결과를 저장해 둔 뒤 `ApplicationReadyEvent` 수신 메서드를 직접 부르면 자동 turn 이 열린다
  - 자동 turn 의 답에 `regenerate` 를 보내면 거절된다
  - hub 에 구독자를 걸면 `system`, `started`, `done` 을 받는다
- 기존 테스트는 test profile 에서 깨우기가 꺼져 있으므로 바꾸지 않는다. `./gradlew test` 전체가 통과해야 한다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
grep -rn "DelegationFinished" backend/src/main/java | wc -l
grep -n "delegation-wake" backend/src/test/resources/application-test.yml
```

첫 `grep` 은 3 이상(정의, 발행, 수신), 둘째는 한 줄 이상이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeProperties.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationFinished.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/OrphanedExecutionSweeper.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationEventHub.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 신규 |
