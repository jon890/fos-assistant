# Phase 02. Hermes 의 답 하나를 실행 줄과 대화에 적는다

**Execution profile**: deep

## 목표

`RUNNING` 으로 남은 실행 줄 하나와 Hermes 가 돌려준 끝난 결과(또는 실패 코드)를 받아, 보통 turn 과 같은 기록 경로로 실행 줄과 대화에 적는 `RecoveredRunRecorder` 를 만든다.
같은 실행을 몇 번 적으려 해도 한 번만 적힌다.

**범위 외**: Hermes 에 묻는 것과 기다리는 것, turn 잠금, 기동 시점(phase 03). 이 phase 에서 `OrphanedExecutionSweeper` 는 그대로 둔다.

## 컨텍스트

- 실행 줄을 끝내는 경로는 `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` 에 있다
  - `complete(AgentExecution, Agent, HermesRunResult, ModelChoice requested)` 와 답을 같은 저장에서 적는 `complete(..., String outputText)`
  - `cancel(AgentExecution, Agent, HermesRunResult, ModelChoice requested)` 와 `cancel(..., String outputText)`
  - `fail(AgentExecution, String errorCode)`. 사용량을 적지 않는다
  - `fail(AgentExecution execution, Agent agent, HermesRunResult result, ModelChoice requested, String errorCode)`. 실패 응답의 사용량과 실제 모델을 남긴다. `result` 가 null 이면 위의 `fail` 과 같다
- 대화 turn 이 성공으로 끝날 때 하는 일은 `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 의 `finish(PendingTurn, HermesRunResult, ModelChoice)` 에, 취소로 끝날 때 하는 일은 `cancel(PendingTurn, HermesRunResult, ModelChoice)` 에 있다. 실패는 `runTurn` 끝의 `executions.fail(...)` 과 `append(pending, ExecutionEventType.RUN_FAILED, ...)` 다. 그 자리의 `fail` 호출이 몇 인자 판인지 직접 읽는다. 오류 코드는 `result.providerBlocked()` 면 `ErrorCode.PROVIDER_BLOCKED.name()`, 아니면 `ChatService.hermesStatus(result)` 와 같은 값(`status` 가 null 이면 `UNKNOWN`, 아니면 대문자)이다
- 위임 실행이 끝날 때 하는 일은 `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` 의 `run(...)` 끝에 있다. 답은 `private String clip(String)` 과 `private String partialOutput(String)` 으로 `DelegationProperties.outputMaxChars()` 까지 자른다
- 위임이 끝났다는 알림은 `orchestration.application.DelegationFinished(Long conversationId, Long executionId)` 다. `ApplicationEventPublisher` 로 내면 `chat.application.NextTurnDispatcher.onDelegationFinished` 가 받는다
- 실행 줄을 잠그는 조회는 `AgentExecutionRepository.lockById(Long id)`(`PESSIMISTIC_WRITE`)다. 트랜잭션 안에서만 쓴다. 트랜잭션은 `TransactionTemplate` 으로 연다(`ChatService` 가 `transactions` 로 쓴다)
- 실행 사건은 `ExecutionEventRecorder.record(AgentExecution, ExecutionEventType, String detail, int sequence)` 로 만들고 `ExecutionEventRepository.save` 로 저장한다. 그 실행의 마지막 순번은 `ExecutionEventRepository.lastSequence(Long id)` 다(없으면 0)
- 메시지는 `ChatMessage.fromAssistant(Long conversationId, String content, Long executionId)` 와 `ChatMessage.regeneratedAnswer(Long conversationId, String content, Long executionId, Long replacesMessageId)` 로 만든다. `ChatMessageRepository` 에는 `findByConversationIdOrderByIdAsc(Long)` 가 있다. `ChatService.regenerate` 가 마지막 유효 메시지를 고르는 방식(`activeMessages`)을 읽고 같은 기준을 쓴다
- 보통 turn 은 답을 저장한 뒤 그 turn 이 대화 폴더에 만든 HTML 을 답에 묶는다. `ChatService.recorded` 가 `artifacts.recordTurn(Long conversationId, Long messageId, Instant turnStartedAt)`(`chat.application.ArtifactService`)을 부른다. `messageId` 가 null 이면 아무것도 하지 않고, 실패해도 예외를 올리지 않는다
- 대화의 session 은 `ConversationRepository.touchSession(Long id, String sessionId, Instant now)` 로 적는다
- 화면 알림은 `ConversationEventHub.publish(Long conversationId, ChatEvent)` 다. `ChatEvent.done(UUID conversationId, Long messageId, Long executionId)`, `ChatEvent.stopped(UUID, Long, Long)`, `ChatEvent.error(String code, String message)` 를 쓴다. `Conversation.publicId()` 가 UUID 다
- 에이전트는 `AgentService.findById(Long)`(`Optional<Agent>`)로 읽는다. 지운 에이전트도 행은 남는다. 흐름은 `FlowRegistry.find(agent.flow())` 가 null 이 아니면 붙어 있다
- 요청한 모델 선택은 실행 줄에서 되살린다. `ModelChoice.stored(row.provider(), row.model(), row.reasoningEffort())`. 실행 줄은 시작할 때 요청 값을 적어 두기 때문이다. `ModelChoice.stored` 의 정의를 읽고 null 을 받는지 확인한다
- 패키지 방향 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 가 검사한다. `chat` 은 이미 `usage`, `orchestration`, `hermes`, `agent` 를 쓴다
- `application` 은 타입 하나에 파일 하나다. 저장되지 않는 enum 은 `<기능>.application.model` 에 둔다(`backend/AGENTS.md`). `chat/application/model` 이 이미 있다

**근거 문서**: `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」(「실행의 종류마다 적는 것이 다르다」 표와 「갈리는 지점」 표), `docs/adr/ADR-059-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`

## 의도 메모

- **멱등의 근거는 실행 줄 하나다.** 트랜잭션에서 `lockById` 로 잠그고 `status` 가 `RUNNING` 이 아니면 아무것도 하지 않고 거짓을 돌려준다. 메시지, 사건, session 은 그 트랜잭션 안에서만 적는다. 화면 알림과 `DelegationFinished` 는 트랜잭션이 끝난 뒤, 실제로 적었을 때만 낸다
- `ExecutionRecorder` 의 메서드는 넘겨받은 엔티티를 고쳐 `save` 한다. 잠근 뒤 읽은 엔티티를 넘긴다. 부르는 쪽이 들고 있던 옛 엔티티를 넘기지 않는다
- `ExecutionRecorder.complete` 는 실제로 돈 모델을 알려고 Hermes 세션 조회를 할 수 있다(`served`). 잠금을 쥔 채 HTTP 를 부르게 되지만 한 실행에 한 번이고 `hermes.read-timeout` 안에 끝난다. 받아들인다
- 다시 붙어 끝난 대화 turn 은 `memoryProposer.proposeFrom` 과 `starterSuggestions.refreshIfStale` 을 부르지 않는다(ADR-059 의 「감당할 것」)
- 대기 줄을 멈추는 것(`pendingMessages.markHeld`)과 turn 잠금은 이 클래스가 하지 않는다. phase 03 의 `RestartReconciler` 가 잠금을 풀기 전에 한다
- `ChatService` 는 이미 1400줄이 넘는다. 여기에 더하지 않고 새 클래스로 둔다. `ChatService.finish` 를 고쳐 함께 쓰게 만들지 않는다. 다른 워커가 같은 파일을 고치고 있다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationOutput.java` (신규)

`AgentRunner` 의 `clip`, `partialOutput`, `TRUNCATED_NOTICE` 를 옮긴 `@Component`. `DelegationProperties` 를 받는다.

```java
public String clip(String output)      // null 이면 "". 상한을 넘으면 자르고 잘렸다는 한 줄을 붙인다
public String partial(String output)   // 비었으면 null, 아니면 clip
```

`AgentRunner` 는 이것을 주입받아 쓰고 자기 사본을 지운다. `TRUNCATED_NOTICE` 를 가리키는 기존 테스트가 있으면(`git grep TRUNCATED_NOTICE backend/src/test`) 새 위치를 가리키게 고치고 「변경 파일」 에 더한다.

### 2. `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java`

`boolean existsByExecutionId(Long executionId);` 를 더한다. 그 실행의 메시지가 이미 있는지 본다.

### 3. `backend/src/main/java/com/bifos/assistant/chat/application/model/RecoveredRunKind.java` (신규)

```java
/** 기동 때 남은 실행의 종류다. 종류마다 적는 것과 아직 돌 때 하는 일이 다르다. */
public enum RecoveredRunKind { CHAT_TURN, DELEGATION, FLOW, AUXILIARY }
```

판정은 `RecoveredRunRecorder.kindOf(AgentExecution row)` 가 한다.

| 순서 | 조건 | 종류 |
| --- | --- | --- |
| 1 | `row.delegationKey() != null` | `DELEGATION` |
| 2 | 뿌리 실행(`row.treeRootId()` 로 읽은 줄. 자신이 뿌리면 자신)의 에이전트에 흐름이 있다 | `FLOW` |
| 3 | `row.parentExecutionId() == null` 이고 `row.conversationId() != null` | `CHAT_TURN` |
| 4 | 그 밖 | `AUXILIARY` |

뿌리 줄이나 그 에이전트를 읽지 못하면 2 를 건너뛴다. `AgentExecution.treeRootId()` 의 정의를 읽고 뿌리 자신일 때 무엇을 주는지 확인한다.

### 4. `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` (신규)

`@Service`. 공개 메서드는 셋이다.

```java
/** 끝난 결과를 적는다. 이미 끝난 줄이면 거짓이다. */
public boolean settle(Long executionId, HermesRunResult result)

/** Hermes 의 결과 없이 FAILED 로 적는다. 이미 끝난 줄이면 거짓이다. */
public boolean failWithout(Long executionId, String errorCode)

public RecoveredRunKind kindOf(AgentExecution row)
```

`settle` 은 트랜잭션에서 줄을 잠그고 `RUNNING` 인지 본 뒤, 에이전트를 읽는다. 에이전트 행이 없으면 `failWithout(executionId, "ORPHANED")` 과 같게 적는다. 그 뒤 종류와 `result` 에 따라 아래 표대로 적는다.

| 종류 | `result.succeeded()` | `"cancelled".equalsIgnoreCase(result.status())` | 그 밖(실패) |
| --- | --- | --- | --- |
| `CHAT_TURN` | `complete(row, agent, result, requested)`. `ASSISTANT` 메시지 저장. `touchSession`. `RUN_COMPLETED` | `cancel(row, agent, result, requested)`. 답이 비어 있지 않으면 `ASSISTANT` 메시지 저장. session 이 있으면 `touchSession`. `RUN_CANCELLED` | `fail(row, agent, result, requested, code)`. `RUN_FAILED`(detail 은 code) |
| `DELEGATION` | `complete(row, agent, result, requested, output.clip(result.output()))`. `RUN_COMPLETED` | `cancel(row, agent, result, requested, output.partial(result.output()))`. `RUN_CANCELLED` | `fail(row, agent, result, requested, code)`. `RUN_FAILED` |
| `FLOW` | 뿌리 줄(`parentExecutionId() == null`)이면 `fail(row, agent, result, requested, "ORPHANED")`, 자식이면 `complete(row, agent, result, requested)` | `cancel(row, agent, result, requested)` | `fail(row, agent, result, requested, code)` |
| `AUXILIARY` | `complete(row, agent, result, requested)` | `cancel(row, agent, result, requested)` | `fail(row, agent, result, requested, code)` |

- `code` 는 `result.providerBlocked()` 면 `ErrorCode.PROVIDER_BLOCKED.name()`, 아니면 `result.status()` 가 null 이면 `UNKNOWN`, 아니면 대문자다
- `requested` 는 `ModelChoice.stored(row.provider(), row.model(), row.reasoningEffort())`
- `CHAT_TURN` 의 메시지: `messages.existsByExecutionId(row.id())` 가 참이면 저장하지 않는다. 그 대화의 마지막 유효 메시지가 `ASSISTANT` 이면 `ChatMessage.regeneratedAnswer(conversationId, answer, row.id(), last.id())`, 아니면 `ChatMessage.fromAssistant(conversationId, answer, row.id())`. 성공의 `answer` 는 `result.output()`(null 이면 `""`)
- 끝 사건의 순번은 `executionEvents.lastSequence(row.id()) + 1`. 사건 저장이 실패해도 실행 줄과 메시지는 남긴다(`ChatService.store` 와 `AgentRunner.append` 가 같은 판단을 한다). 그래서 사건은 트랜잭션이 끝난 뒤 따로 저장하고 예외는 경고 로그로만 남긴다
- 대화가 지워졌거나 없으면(`CHAT_TURN`) 실행 줄만 적고 메시지와 알림은 건너뛴다
- `CHAT_TURN` 의 성공과 취소에서 메시지를 저장했으면, 트랜잭션이 끝난 뒤 `artifacts.recordTurn(conversationId, messageId, row.startedAt())` 을 부른다. 보통 turn 이 폴더를 만들기 직전 시각을 쓰는 것과 달리 실행 줄의 시작 시각을 쓴다. 실행 줄은 폴더를 만든 뒤에 생기므로 그 시각 뒤에 바뀐 HTML 은 모두 이 turn 의 것이다

트랜잭션이 끝난 뒤, 실제로 적었을 때만 한다.

| 종류 | 알림 |
| --- | --- |
| `CHAT_TURN` 성공 | `hub.publish(conversationId, ChatEvent.done(publicId, messageId, executionId))` |
| `CHAT_TURN` 취소 | `ChatEvent.stopped(publicId, messageId 또는 null, executionId)` |
| `CHAT_TURN` 실패 | `ChatEvent.error(ErrorCode.HERMES_RUN_FAILED.name(), "the agent run did not complete")`. provider 가 막힌 것이면 `ErrorCode.PROVIDER_BLOCKED.name()` 과 `ChatService.runTurn` 이 던지는 글 |
| `DELEGATION` (세 결과 모두) | `events.publishEvent(new DelegationFinished(row.conversationId(), row.id()))`. `conversationId` 가 null 이면 내지 않는다 |

알림이 예외를 던져도 `settle` 은 참을 돌려준다. 경고 로그만 남긴다.

`failWithout` 은 같은 잠금과 `RUNNING` 확인 뒤 `executions.fail(row, errorCode)` 로 적는다. 알림은 위 표의 실패 줄과 같다(`CHAT_TURN` 은 `error`, `DELEGATION` 은 `DelegationFinished`). 사건은 남기지 않는다.

### 5. `backend/src/test/java/com/bifos/assistant/chat/RecoveredRunRecorderTest.java` (신규)

`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(ChatServiceTest.StubRuntime.class)`. 준비 방식은 `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` 와 `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` 를 따른다. 실행 줄은 저장소로 직접 `RUNNING` 과 `hermesRunId` 를 가진 채 만든다.

| 경우 | 기대 |
| --- | --- |
| 대화 turn, `completed` 와 usage | 줄이 `SUCCEEDED`, 토큰과 비용이 적혔다. 그 대화에 `executionId` 를 가진 `ASSISTANT` 메시지가 하나. hub 구독자가 `done` 을 받았다 |
| 같은 `settle` 을 한 번 더 | 거짓. 메시지는 여전히 하나, 사건도 늘지 않는다, 줄의 `finishedAt` 이 바뀌지 않는다 |
| 대화 turn, `failed` 와 usage | 줄이 `FAILED`, `errorCode` 가 `FAILED`, 토큰이 적혔다. 메시지가 없다. hub 가 `error` 를 받았다 |
| 대화 turn, `cancelled` 와 일부 답 | 줄이 `CANCELLED`, 메시지가 하나 |
| 대화 turn 이 `completed` 이고 그 대화 폴더에 실행 시작 뒤 만든 HTML 이 있다 | 그 결과물이 새 답 메시지에 묶였다. 준비와 단언은 `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 의 방식을 따른다 |
| 마지막 메시지가 `ASSISTANT` 인 대화의 turn 이 `completed` | 새 메시지가 그 답을 다시 생성한 것으로 저장된다(`ChatRegenerateTest` 가 읽는 칸과 같은 칸) |
| 위임 실행, `completed` | 줄이 `SUCCEEDED`, `outputText` 가 답. `DelegationFinished` 가 났다(`@RecordApplicationEvents` 또는 테스트 리스너). `AgentExecutionRepository.findUndeliveredResults(conversationId)` 에 그 줄이 있다 |
| 위임 실행, 답이 `output-max-chars` 보다 길다 | `outputText` 가 잘렸고 잘렸다는 줄로 끝난다 |
| 흐름 에이전트의 뿌리 줄, `completed` | 줄이 `FAILED`, `errorCode` 가 `ORPHANED`, 토큰이 적혔다. 메시지가 없다 |
| `failWithout(id, "REMOTE_RUN_LOST")` | 줄이 `FAILED`, `errorCode` 가 `REMOTE_RUN_LOST`. 두 번째 호출은 거짓 |
| 이미 `SUCCEEDED` 인 줄에 `settle` | 거짓. 줄이 그대로다 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.RecoveredRunRecorderTest' --tests 'com.bifos.assistant.orchestration.*' --tests 'com.bifos.assistant.usage.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest
```

둘 다 종료 코드 0. `orchestration` 테스트는 `DelegationOutput` 으로 옮긴 자르기가 그대로인지, `architecture` 는 패키지 방향을 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationOutput.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/RecoveredRunKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/RecoveredRunRecorderTest.java` | 신규 |
