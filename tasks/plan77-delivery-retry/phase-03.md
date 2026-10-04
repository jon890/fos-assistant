# Phase 03. 저장된 결과만 다시 전달하는 API

**Execution profile**: deep

## 목표

`FAILED` 나 `STOPPED` 인 전달 묶음을 사용자가 다시 전달하는 SSE 경로를 열고, 이력 API 가 각 묶음의 상태를 마지막 알림 줄에 실어 준다.
다시 전달은 저장된 실행 줄과 승인 줄에서 결과를 다시 읽어 부모에 넘길 뿐, 자식 실행이나 커넥터 호출을 다시 하지 않는다.

**범위 외**: 웹 경로와 화면(phase 05), e2e 시나리오(phase 04). 자동 재시도는 만들지 않는다.

## 컨텍스트

- phase 01 이 만든 것: `chat.application.ResultDeliveryRecorder`(`open`, `attachExecution`, `finish`, 상수 `DELEGATION_SOURCE`), `ChatService` 의 private `runDeliveryTurn(CurrentUser owner, Routed routed, String input, Long attemptId, TurnHandle handle, Consumer<ChatEvent> onEvent)`(시도를 닫고 끝 사건을 낸다), `TurnIntent.DelegationResults(Long attemptId)`, `AutoTurnResultSource.source()`, `chat.application.model.DeliveryItemRef`, enum `DeliveryStatus.retryable()`
- phase 02 가 만든 것: `ResultDeliveryRecorder.finishByExecution`, `closeLeftovers`, `ResultDeliveryRecovery`
- 자동 turn 의 입력은 `ChatService.delegationInput(results, resultAgents)` 와 `AutoTurnResult.input()` 들을 빈 줄로 이은 글이다. 연결용 에이전트의 답과 에이전트 행이 없는 결과는 `ExternalData.wrap` 으로 감싼다
- 다시 생성 경로가 본보기다. 컨트롤러는 `ChatController.regenerate`(`access.requireOwnId` 로 SSE 전에 404, `streams.open(send -> chat.regenerate(user, id, event -> event.forViewer(user).ifPresent(send)))`), 서비스는 `ChatService.regenerate`(대화 잠금을 직접 열고 `finally` 에서 닫는다)다
- SSE 안에서 던진 `ApiException` 은 `ChatEventStreams` 가 `error` 사건으로 보낸다
- 에이전트를 지금 읽을 수 있는지는 `Agent.isReadableBy(Long userId)` 다. 지운 것은 `isDeleted()`, 꺼진 것은 `!enabled()` 다
- 이력 API 는 `ChatController.messages` 가 `ChatDtos.MessageView` 를 만든다. 지금 칸은 `id, role, content, senderName, executionId, hasChildren, switchedTo, replacesMessageId, createdAt, attachments, artifacts, activity, status` 다
- 승인 줄은 `connector.infra.ConnectorActionRepository`, 실행한 상태 묶음은 `ConnectorActionService` 의 상수 `EXECUTED`(`SUCCEEDED`, `FAILED`, `UNKNOWN`)다. 승인 결과를 모델 입력으로 바꾸는 것은 `ConnectorActionResultSource.input(...)` 이다

**근거 문서**: `docs/backend/agent-delegation.md` 의 「다시 전달할 때」 와 「다시 전달이 갈리는 지점」, `docs/frontend/chat.md` 의 「결과 다시 전달」, `docs/flow.md` 의 「실행이 실패할 때」, `docs/backend/execution-limit.md` 의 「한도에 닿을 때」, `docs/adr/ADR-070-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md`

## 의도 메모

- 버린 안: 첫 시도 때의 Hermes 입력 원문을 저장해 그대로 보내기. 결과 본문을 두 곳에 두게 되고, 다시 읽는 자리에서 그 줄이 지금도 그 사용자의 것인지 보는 기회를 잃는다(ADR-070)
- 판정 순서는 「다시 전달이 갈리는 지점」 표의 순서다. 대화, 묶음, 상태, 에이전트를 잠금 전에 보고, 잠금을 연 뒤 결과를 다시 읽고 조건부 update 로 시작한다. 잠금 전에 본 상태는 빠른 거절일 뿐이고, 활성 시도를 하나로 지키는 것은 조건부 update 다
- 다시 전달의 알림 줄은 `"맡긴 일의 결과를 다시 전해요"` 하나다. 결과마다 알림 줄을 다시 남기지 않는다
- 다시 전달은 자동 turn 의 지시 대신 다시 전달용 지시를 쓴다. 첫 시도가 시간 초과로 끝났으면 원격 run 이 이미 이어서 일을 맡겼을 수 있어, 「이어서 할 일이 있으면 진행한다」 를 그대로 주면 같은 일을 다시 맡길 수 있다
- `ResultDeliveryRecordTest` 의 테스트 전용 `AutoTurnResultSource` 는 새 메서드 `resultsFor` 를 구현하도록 고친다. 단언은 바꾸지 않는다
- 다시 전달 turn 의 사건은 다시 생성처럼 요청한 창에만 간다. 같은 대화를 연 다른 창은 `/running` 폴링으로 본다
- 다시 전달은 사람이 요청한 turn 이다. 알림 줄을 저장하는 트랜잭션에서 `conversationWriter.resetAutoTurns` 를 부른다. `incrementAutoTurns` 는 부르지 않는다
- 이력 API 는 오류 코드를 싣지 않는다. 원인은 관리자 영역의 실행 상세가 보인다(ADR-063)
- 사용자 실행 한도는 `turns.open` 이 판정한다. `USER_BUSY` 면 묶음을 바꾸지 않고 재시도를 예약하지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`

- `DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND)`: 그 대화의 묶음이 아니거나 없는 묶음이다. 남의 대화의 묶음도 같은 응답이다
- `DELIVERY_NOT_RETRYABLE(HttpStatus.CONFLICT)`: 묶음이 `DELIVERING` 이나 `DELIVERED` 다. 넘길 결과가 남지 않았거나 대화에 흐름이 붙었다
각 값에 한 줄 Javadoc 을 단다.

### 2. 승인 결과를 열쇠로 다시 읽는다

- `backend/src/main/java/com/bifos/assistant/chat/application/AutoTurnResultSource.java` 에 `List<AutoTurnResult> resultsFor(Long conversationId, Long userId, List<String> keys);` 를 더한다. 그 대화와 그 사용자의 결과만, `keys` 의 순서로 낸다. 없거나 남의 것은 뺀다
- `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` 에 `List<ConnectorAction> findByConversationIdAndUserIdAndPublicIdIn(Long conversationId, Long userId, Collection<UUID> publicIds)` 를 더한다
- `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` 에 `@Transactional(readOnly = true) List<ConnectorActionResult> resultsFor(Long conversationId, Long userId, List<UUID> actionIds)` 를 더한다. 상태가 `EXECUTED` 안인 줄만 낸다. 모양은 private `undelivered` 와 같은 `ConnectorActionResult` 다
- `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` 가 `resultsFor` 를 구현한다. `notice` 와 `input` 은 지금 메서드를 그대로 쓴다. UUID 로 읽지 못하는 열쇠는 뺀다

### 3. 저장소

- `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryRepository.java`
  - `Optional<ResultDelivery> findByIdAndConversationId(Long id, Long conversationId)`
  - `List<ResultDelivery> findByConversationId(Long conversationId)`
  - `@Modifying` `int claimRetry(Long id, Collection<DeliveryStatus> from, Instant at)`: `update ResultDelivery d set d.status = com.bifos.assistant.chat.domain.type.DeliveryStatus.DELIVERING, d.attemptCount = d.attemptCount + 1, d.updatedAt = :at where d.id = :id and d.status in :from`
- `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryAttemptRepository.java`
  - `List<ResultDeliveryAttempt> findByDeliveryIdIn(Collection<Long> deliveryIds)`

### 4. `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecorder.java`

- `ResultDelivery require(Long conversationId, Long deliveryId)`: 없으면 `ApiException(DELIVERY_NOT_FOUND)`
- `List<DeliveryItemRef> itemsOf(Long deliveryId)`: 넣은 순서
- `int claimRetry(Long deliveryId, Instant now)`: `@Transactional`(부르는 쪽에 참여). `FAILED` 나 `STOPPED` 에서만 `DELIVERING` 으로 바꾸고 새 시도 번호(늘린 뒤의 `attempt_count`)를 돌려준다. 바뀐 줄이 없으면 `ApiException(DELIVERY_NOT_RETRYABLE)`
- `Long addAttempt(Long deliveryId, int attemptNo, Long noticeMessageId, Instant now)`: `@Transactional`(참여). `RUNNING` 시도를 저장하고 번호를 돌려준다
- `Map<Long, DeliveryState> statesByNotice(Long conversationId)`: 그 대화의 묶음마다 `attempt_no` 가 가장 큰 시도의 `notice_message_id` 를 열쇠로, 묶음 번호와 묶음 상태를 값으로 낸다. `notice_message_id` 가 빈 시도는 뺀다. 질의는 묶음 한 번, 시도 한 번이다
- 새 record `backend/src/main/java/com/bifos/assistant/chat/application/model/DeliveryState.java`: `DeliveryState(Long deliveryId, DeliveryStatus status)`

### 5. `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java`

- 상수 `static final String RETRY_NOTICE = "맡긴 일의 결과를 다시 전해요";`
- `TurnIntent.DelegationResults` 에 `boolean retry` 를 더해 `DelegationResults(Long attemptId, boolean retry)` 로 둔다. 자동 turn 은 거짓, 다시 전달은 참이다. `TurnIntent` 에 `DELIVERY_RETRY_INSTRUCTION` 을 두고 `instructionFor` 가 참일 때 그것을 낸다. 글은 「앞서 정리하지 못한 맡긴 일의 결과를 다시 전한다. 결과를 사용자에게 정리해 전하고 답을 마친다. 이 결과 때문에 일을 새로 맡기거나 같은 도구를 다시 부르지 않는다. 」 다음에 `DELEGATION_RESULTS_INSTRUCTION` 의 `<external-data>` 문장과 승인한 동작 문장을 그대로 잇는다
- 입력 조립을 자동 turn 과 다시 전달이 함께 쓰도록 private 메서드 하나로 묶는다. 위임 결과 목록과 `AutoTurnResult` 목록을 받아 지금 `runDelegationResults` 와 같은 글을 만든다. 자동 turn 의 입력 글은 한 글자도 바뀌지 않아야 한다. `DelegationWakeServiceTest` 와 `ConnectorActionDeliveryTest` 의 입력 단언이 그것을 지킨다
- `public void retryDelivery(CurrentUser user, Long conversationId, Long deliveryId, Consumer<ChatEvent> onEvent)`
  1. `access.requireOwn(user, conversationId)`
  2. `resultDeliveries.require(conversationId, deliveryId)`. 상태가 `retryable()` 이 아니면 `DELIVERY_NOT_RETRYABLE`
  3. 대화의 에이전트를 `agents.requireById` 로 읽는다. 지웠거나 `!isReadableBy(user.id())` 면 `AGENT_NOT_FOUND`, 꺼졌으면 `AGENT_DISABLED`, `flows.find(agent.flow()) != null` 이면 `DELIVERY_NOT_RETRYABLE`
  4. `TurnHandle handle = turns.open(user.id(), conversationId)`. `CONVERSATION_BUSY` 와 `USER_BUSY` 는 그대로 올라간다. 이 뒤는 `try` 안이고 `finally` 에서 `turns.close(handle)`
  5. 항목을 다시 읽는다. `DELEGATION_SOURCE` 항목은 `executionRepository.findAllById` 로 읽고 `userId` 와 `conversationId` 가 같고 상태가 `SUCCEEDED` 나 `FAILED` 인 줄만, 항목 순서대로 쓴다. 그 밖의 항목은 `source()` 가 같은 `AutoTurnResultSource` 의 `resultsFor(conversationId, user.id(), keys)` 로 읽는다. 맞는 구현이 없으면 경고 로그를 남기고 뺀다. 남은 것이 없으면 `DELIVERY_NOT_RETRYABLE`
  6. 위의 입력 조립 메서드로 글을 만들고 `route(user, conversationId, input, null, List.of())` 로 `Routed` 를 얻는다. 잠금을 잡은 뒤의 상태로 다시 본다. `routed.flow() != null` 이면 `DELIVERY_NOT_RETRYABLE`, `!routed.agent().isReadableBy(user.id())` 면 `AGENT_NOT_FOUND`. `runDelegationResults` 가 잠금 뒤 흐름을 다시 거르는 것과 같다
  7. 한 트랜잭션에서 `int attemptNo = resultDeliveries.claimRetry(deliveryId, now)`, `RETRY_NOTICE` 의 `SYSTEM` 줄 저장, `resultDeliveries.addAttempt(deliveryId, attemptNo, line.id(), now)`, `conversationWriter.resetAutoTurns(conversationId)`. 커밋 뒤 `ChatEvent.system` 을 낸다
  8. `runDeliveryTurn(user, routed, input, attemptId, handle, onEvent)`
- `public Map<Long, DeliveryState> deliveryStates(Long conversationId)`: `resultDeliveries.statesByNotice` 를 그대로 낸다. 컨트롤러가 주인 확인을 마친 번호로 부른다

### 6. `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` 와 `ChatController.java`

- `ChatDtos` 에 `public record DeliveryView(Long id, String status) {}` 를 더하고 `MessageView` 끝에 `DeliveryView delivery` 를 더한다. Javadoc 의 `@param delivery` 는 「이 알림 줄이 묶음의 마지막 시도의 마지막 알림 줄이면 그 묶음의 번호와 상태. 아니면 null」 이다
- `ChatController.messages` 가 `chat.deliveryStates(number)` 를 한 번 읽어 줄마다 `DeliveryView` 를 채운다. `SYSTEM` 이 아닌 줄은 null 이다
- `ChatController` 에 `@PostMapping(path = "/conversations/{conversationId}/deliveries/{deliveryId}/retry/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` 인 `retryDelivery(@PathVariable UUID conversationId, @PathVariable Long deliveryId)` 를 더한다. 모양은 `regenerate` 와 같다

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryTest.java`

틀은 phase 01 의 `ResultDeliveryRecordTest` 와 같다(`assistant.delegation-wake.enabled=true`). 결과 글은 지어낸 글이다. 다시 전달은 `chat.retryDelivery(dad, conversationId, deliveryId, events::add)` 를 테스트 스레드에서 부른다.

| 검사 | 준비 | 기대 |
| --- | --- | --- |
| provider 실패 뒤 다시 전달 | 위임 결과 하나. 첫 자동 turn 은 대역 Hermes 가 `failed`, 다시 전달 때는 완료 | 사건 순서 `system`, `started`, `done`. `RETRY_NOTICE` 줄과 `ASSISTANT` 줄이 더해짐. 묶음 `DELIVERED`, `attempt_count` 2, 시도 1 `FAILED`, 시도 2 `SUCCEEDED`. 다시 전달의 Hermes 입력이 첫 시도의 입력과 같고 instructions 끝이 `DELIVERY_RETRY_INSTRUCTION` 임. 대역 Hermes 가 받은 명령이 모두 부모 profile 이고 자식 profile 명령은 0. 위임 실행 줄의 상태와 `output_text` 와 `result_delivered_at` 이 그대로 |
| 중지한 전달 | 첫 자동 turn 을 중지 | 다시 전달이 성공하고 묶음 `DELIVERED` |
| 끝난 전달 | `DELIVERED` 묶음 | `DELIVERY_NOT_RETRYABLE`. 시도와 알림 줄이 늘지 않음 |
| 버튼을 연달아 누른다 | `stub().holdSubmits()` 로 첫 다시 전달을 submit 에서 붙잡은 채 다른 스레드에서 한 번 더 | 묶음이 이미 `DELIVERING` 이라 두 번째는 `DELIVERY_NOT_RETRYABLE`. 붙잡은 동안 `chat.deliveryStates` 가 `DELIVERING` 이고 `chat.running` 이 그 실행을 돌려줌(재접속한 창이 보는 것). 풀어 준 뒤 한 번 더 부르면 `DELIVERY_NOT_RETRYABLE`. 시도는 모두 둘, `ASSISTANT` 줄은 하나 |
| 다른 turn 이 돌고 있다 | 같은 대화에 사용자 turn 을 `holdSubmits` 로 붙잡음 | `CONVERSATION_BUSY`. 묶음 `FAILED` 그대로 |
| 잠금 뒤 다른 요청이 먼저 바꿨다 | `FAILED` 묶음을 `DELIVERING` 으로 바꾼 뒤 `ResultDeliveryRecorder.claimRetry` 를 트랜잭션 안에서 직접 부름 | `DELIVERY_NOT_RETRYABLE` 이고 그 트랜잭션에서 저장한 알림 줄도 되돌아감 |
| 남의 대화와 다른 대화의 묶음 | 다른 사용자, 같은 사용자의 다른 대화 번호 | 앞은 `CONVERSATION_NOT_FOUND`, 뒤는 `DELIVERY_NOT_FOUND` |
| 권한이 사라졌다 | 대화의 에이전트를 다른 사용자 소유 `PRIVATE` 로 바꿈(`Agent.changeAccess`). 따로 꺼짐 | 앞은 `AGENT_NOT_FOUND`, 뒤는 `AGENT_DISABLED`. 묶음은 `FAILED` 그대로, Hermes 제출 없음 |
| 재기동으로 중단된 전달 | 시도 `RUNNING` 과 실행 줄 없음을 만들고 `ResultDeliveryRecorder.closeLeftovers` | 다시 전달이 성공하고 시도 2 가 `SUCCEEDED` |
| 결과 줄이 지워졌다 | 항목의 위임 실행 줄을 지움 | `DELIVERY_NOT_RETRYABLE`. 묶음 상태 그대로 |
| 이력의 묶음 상태 | 첫 실패 뒤와 다시 전달 뒤 `chat.deliveryStates(conversationId)` | 실패 뒤에는 첫 알림 줄 번호에 `FAILED`. 다시 전달 뒤에는 `RETRY_NOTICE` 줄 번호에 `DELIVERED` 이고 첫 알림 줄은 빠짐 |

사용자 실행 한도 검사는 속성이 달라 `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryUserLimitTest.java` 에 따로 둔다.
`@SpringBootTest(properties = {"assistant.delegation-wake.enabled=true", "assistant.user-execution.max-running=2"})` 이고, 자리를 채우는 방법은 `DelegationWakeUserLimitTest` 를 따른다.
실패한 묶음을 만든 뒤 자리를 모두 채우고 다시 전달하면 `USER_BUSY` 이고, 묶음은 `FAILED` 그대로이며, 1초를 기다려도 제출이 없다. 자리를 비운 뒤 다시 전달하면 성공한다.

### 8. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ConnectorDeliveryRetryTest.java`

`ConnectorActionDeliveryTest` 의 준비를 옮겨 쓴다. `HermesConnectorClient` 는 `@MockitoBean` 이다.

| 검사 | 기대 |
| --- | --- |
| 승인해 실행한 결과의 자동 turn 이 실패한 뒤 다시 전달 | `connector.execute` 호출이 다시 전달 전후 모두 한 번(`verify(connector, times(1))`). 다시 전달의 입력에 「승인한 동작의 결과가 도착했다.」 와 `<external-data>` 가 있음 |
| 결과를 모르는 `UNKNOWN` 승인 결과의 다시 전달 | 입력에 「실행 여부를 알 수 없다. 다시 실행하지 말고 사용자에게 확인을 부탁한다.」 가 있고 `execute` 는 한 번. 상수 `UNKNOWN_INPUT` 은 package-private 이라 글로 견준다 |
| 다른 사용자의 승인 줄 열쇠 | `resultsFor` 가 빈 목록 |

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.ResultDeliveryRetryTest' --tests 'com.bifos.assistant.chat.ResultDeliveryRetryUserLimitTest' --tests 'com.bifos.assistant.chat.ConnectorDeliveryRetryTest'
./gradlew test --tests 'com.bifos.assistant.chat.DelegationWakeServiceTest' --tests 'com.bifos.assistant.chat.ConnectorActionDeliveryTest' --tests 'com.bifos.assistant.chat.DelegationWakeUserLimitTest'
./gradlew test
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
```

기대: 모두 종료 코드 0. 새 저장소 메서드 다섯이 `RepositoryQueryMysqlTest` 에서 실행된다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AutoTurnResultSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryAttemptRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/DeliveryState.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecordTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryUserLimitTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorDeliveryRetryTest.java` | 신규 |
