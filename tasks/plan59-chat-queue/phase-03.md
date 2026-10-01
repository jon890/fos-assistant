# Phase 03. 대기 메시지를 받아 다음 turn 으로 보낸다

**Execution profile**: deep

## 목표

turn 이 도는 동안 보낸 글을 대기 메시지로 저장하고, 그 turn 이 끝나면 쌓인 것을 합쳐 사용자 메시지 하나로 다음 turn 을 연다.
중지로 끝난 turn 뒤에는 보내지 않고 멈춰 둔다.

**범위 외**: 화면(Phase 05), e2e 시나리오(Phase 04), Hermes `steer` 호출(이 계획의 범위가 아니다).

## 컨텍스트

Phase 01 이 `ChatPendingMessage`, `ChatPendingMessageRepository` 를, Phase 02 가 `NextTurnDispatcher`, `TurnClosed`, `TurnCancellation.markStopped` 를 만들었다.
아래 경로는 모두 `backend/src/main/java/com/bifos/assistant/chat/` 기준이다.

- `application/ChatService.java` 의 `runDelegationResults(owner, conversationId, handle, onEvent)` 가 요청 없이 도는 turn 의 본보기다. 부르는 쪽이 잠금을 잡고, `route(...)` 뒤 `runTurn(...)` 을 돌리고 `done` 이나 `stopped` 를 낸다.
- `ChatService.saveQuestion` 의 `TurnIntent.Fresh` 가지가 `transactions.executeWithoutResult` 안에서 `fillBlankTitle`, `messages.save(ChatMessage.fromUser(...))`, `attachments.attach(...)`, `conversations.resetAutoTurns(...)` 를 한다.
- `application/DelegationWakeService.java` 의 `tryWake` 와 `runAutoTurn` 이 「잠금을 잡고 새 가상 스레드에서 turn 을 돌린 뒤 닫는다」 의 본보기다.
- `application/ConversationEventHub.publish(Long conversationId, ChatEvent event)` 가 대화 단위 SSE 로 보낸다.
- `application/ConversationAccess.requireOwn(CurrentUser, Long)` 이 주인을 확인한다. 남의 대화와 지운 대화는 `CONVERSATION_NOT_FOUND` 다.
- `presentation/ChatController.java` 는 `access.requireOwnId(user, UUID)` 로 공개 식별자를 대화 번호로 바꾼다. 요청과 응답 모양은 `presentation/ChatDtos.java` 하나에 둔다(`ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`).
- `application/ChatEvent.java` 는 칸 19개의 record 다. 팩터리는 `ChatEvent.system(UUID conversationId, Long messageId, String content)` 를 본보기로 쓴다. 글은 `text` 칸에 싣는다.
- `application/TurnIntent.java` 의 `record Fresh()` 는 `ChatService` 와 `orchestration/application/ResearchAndBuildFlow.java` 가 `new TurnIntent.Fresh()` 와 `instanceof TurnIntent.Fresh` 로 쓴다.
- 메시지 길이 상한은 `ChatDtos.SendMessageRequest` 의 `@Size(max = 8000)` 이다.
- `shared/error/ErrorCode.java` 에 코드를 더한다. HTTP 상태는 enum 인자가 정한다.
- 시각은 `Clock` 을 주입받아 `Instant.now(clock)` 으로 읽는다. 본보기는 `application/ModelOptionsService.java` 의 두 생성자다.
- 테스트 profile 은 `assistant.delegation-wake.enabled=false` 다. 대기 메시지 경로는 그 설정과 무관하게 돈다.

**근거 문서**: `docs/flow.md` 의 「응답 중에 보낼 때」 절(갈리는 지점 표가 동작 계약이다), `docs/code-architecture.md` 의 「응답 중 대기열」 절과 「경로」 표와 「화면으로 보내는 사건」 표, `docs/data-schema.md` 의 「chat_pending_message」 절, `docs/adr/ADR-047-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md`

## 의도 메모

- 하나씩 차례로 보내는 안은 버렸다. 합쳐 한 번에 보낸다.
- 흐름이 붙은 에이전트는 받지 않는다. 흐름은 질문을 `Flow.run` 안에서 저장해 대기 행 삭제와 한 트랜잭션으로 묶을 수 없다.
- 「turn 이 도는가」 를 먼저 보고 저장할지 바로 보낼지 정하지 않는다. 언제나 저장한 뒤 `tryNext` 를 부른다. 먼저 보고 정하면 보는 순간과 저장하는 순간 사이에 turn 이 닫혔을 때 그 글을 보낼 계기가 없다.
- 사용자 메시지를 저장하기 전에 실패한 대기 행은 멈춰 둔다. 그대로 닫으면 종료 리스너가 같은 행으로 곧바로 다시 연다.
- 멈춤을 메모리에 두지 않는다. 서버가 다시 뜬 뒤 기동 확인이 사용자가 멈춘 글을 보내 버린다.
- 구현이 `docs/` 의 계약과 달라져야 하면 고치기 전에 계획을 쓴 쪽에 알린다. 알린 뒤 같은 커밋에서 그 절을 고친다.

## 작업 항목

### 1. `shared/error/ErrorCode.java`

`CONVERSATION_BUSY` 아래에 둘을 더한다. 한국어 Javadoc 을 단다.

- `PENDING_QUEUE_FULL(HttpStatus.CONFLICT)`: 대기 메시지가 상한에 닿았다.
- `PENDING_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND)`: 취소하려는 대기 메시지가 이미 보내졌거나 없다.

### 2. `application/ChatEvent.java`

- `public static ChatEvent user(UUID conversationId, Long messageId, String content)`: `type` 은 `"user"`, 글은 `text` 칸.
- `public static ChatEvent pending(UUID conversationId)`: `type` 은 `"pending"`, `conversationId` 만 싣는다.

### 3. `application/TurnIntent.java`

`Fresh` 를 아래로 바꾼다. 인자 없는 호출 자리는 그대로 둔다.

```java
/** @param pendingIds 이 turn 이 합쳐 보내는 대기 메시지들. 사용자가 바로 보낸 turn 은 비어 있다 */
record Fresh(List<Long> pendingIds) implements TurnIntent {
    public Fresh {
        pendingIds = List.copyOf(pendingIds);
    }

    public Fresh() {
        this(List.of());
    }
}
```

### 4. `application/PendingQueueChangedException.java` 신규

`RuntimeException` 을 잇는다. 읽은 대기 행이 저장 트랜잭션 전에 취소됐다는 내부 신호다. 밖으로 나가지 않는다.

### 5. `application/ChatService.java`

- 주입에 `ChatPendingMessageRepository pendingMessages` 를 더한다.
- `saveQuestion` 의 `Fresh` 가지: 저장한 `ChatMessage` 를 트랜잭션 밖으로 돌려받게 `transactions.execute` 로 바꾼다. `fresh.pendingIds()` 가 비어 있지 않으면 같은 트랜잭션에서 `pendingMessages.deleteAllByIdIn(ids)` 를 부르고, 돌려받은 수가 `ids.size()` 와 다르면 `PendingQueueChangedException` 을 던져 되돌린다. 트랜잭션이 끝난 뒤 `pendingIds` 가 비어 있지 않으면 `onEvent.accept(ChatEvent.user(conversation.publicId(), saved.id(), text))` 와 `onEvent.accept(ChatEvent.pending(conversation.publicId()))` 를 차례로 낸다.
- `public void runPendingMessages(CurrentUser owner, Long conversationId, TurnCancellation.TurnHandle handle, Consumer<ChatEvent> onEvent)` 를 더한다. 부르는 쪽이 잠금을 이미 잡았다.
  1. `pendingMessages.findByConversationIdOrderByIdAsc(conversationId)` 로 다시 읽는다. 비었거나 `held` 인 행이 하나라도 있으면 돌아간다.
  2. `String text = ChatPendingMessage.merged(rows)`.
  3. `Routed routed = route(owner, conversationId, text, null, List.of())`. `routed.flow() != null` 이면 `ApiException(ErrorCode.CONVERSATION_BUSY, ...)` 를 던진다.
  4. `runTurn(owner, routed, text, new TurnIntent.Fresh(ids), onEvent, true, handle)` 를 돌리고 `runDelegationResults` 와 같이 `done` 이나 `stopped` 를 낸다.
  5. `PendingQueueChangedException` 을 잡으면 1 부터 다시 한다. 세 번째에도 잡히면 `ApiException(ErrorCode.CONVERSATION_BUSY, ...)` 를 던진다.
- `delete(CurrentUser, Long)`: `conversations.deleteIfActive` 가 성공한 뒤 같은 트랜잭션에서 `pendingMessages.deleteAllOf(conversationId)` 를 부른다.

### 6. `application/PendingQueue.java` 신규

```java
/** 한 대화의 대기 줄이다. {@code held} 가 참이면 사용자가 풀 때까지 보내지 않는다. */
public record PendingQueue(boolean held, List<ChatPendingMessage> items) {}
```

### 7. `application/PendingMessageService.java` 신규

`@Service` 다. 주입: `ConversationAccess access`, `AgentService agents`, `FlowRegistry flows`, `ChatPendingMessageRepository pendingMessages`, `ConversationEventHub hub`, `NextTurnDispatcher dispatcher`, `TransactionTemplate transactions`, `Clock clock`(본보기처럼 `Clock.systemUTC()` 를 넘기는 생성자를 둔다).

상수: `static final int MAX_ITEMS = 5;`, `static final int MAX_MERGED_CHARS = 8000;`

| 메서드 | 동작 |
| --- | --- |
| `PendingQueue queue(CurrentUser user, Long conversationId)` | 주인을 확인하고 대기 줄을 읽는다. `held` 는 한 행이라도 멈춰 있으면 참이다 |
| `PendingQueue enqueue(CurrentUser user, Long conversationId, String text)` | 아래 「더하기」 |
| `void cancel(CurrentUser user, Long conversationId, Long pendingId)` | 주인을 확인하고 `deleteOne` 을 트랜잭션에서 부른다. 0 이면 `PENDING_MESSAGE_NOT_FOUND`. 지웠으면 `pending` 사건을 낸다 |
| `PendingQueue release(CurrentUser user, Long conversationId)` | 주인을 확인하고 `markHeld(conversationId, false)` 를 트랜잭션에서 부른다. `pending` 사건을 내고 `dispatcher.tryNext(conversationId)` 를 부른 뒤 대기 줄을 돌려준다 |

더하기:

1. `access.requireOwn` 으로 대화를 얻는다.
2. `agents.requireById(conversation.agentId())` 로 에이전트를 얻는다. 지운 에이전트는 `AGENT_NOT_FOUND`, 꺼진 에이전트는 `AGENT_DISABLED`, `flows.find(agent.flow()) != null` 이면 `CONVERSATION_BUSY` 다. 판정 순서와 메시지는 `ChatService.route` 와 같게 한다.
3. 대화별 잠금 안에서 상한을 보고 저장한다. 잠금은 이 서비스의 메모리에 둔다(예: `ConcurrentHashMap<Long, Object>` 의 값으로 `synchronized`). 행이 `MAX_ITEMS` 개이거나 `ChatPendingMessage.mergedLength(rows, text) > MAX_MERGED_CHARS` 이면 `PENDING_QUEUE_FULL` 이다. 새 행의 `held` 는 지금 멈춘 행이 있으면 참이다.
4. 잠금 밖에서 `hub.publish(conversation.id(), ChatEvent.pending(conversation.publicId()))`.
5. `dispatcher.tryNext(conversation.id())`.
6. 대기 줄을 다시 읽어 돌려준다.

가상 스레드가 `synchronized` 안에서 DB 를 부르는 것이 걸리면 `ReentrantLock` 으로 바꾼다. `presentation/ChatEventStreams.java` 의 `Channel` 이 그 본보기다.

### 8. `application/NextTurnDispatcher.java`

주입에 `ChatPendingMessageRepository pendingMessages`, `ConversationRepository conversations`, `AppUserRepository users`, `ChatService chat`, `ConversationEventHub hub`, `TransactionTemplate transactions` 를 더한다.

- `onTurnClosed(TurnClosed closed)`: `closed.stopped()` 이면 트랜잭션에서 `pendingMessages.markHeld(conversationId, true)` 를 부르고, 바뀐 행이 있으면 `pending` 사건을 낸다. 그 뒤 `tryNext`.
- `tryNext(Long conversationId)`: `tryPending(conversationId)` 가 참이면 돌아간다. 거짓이면 `wake.tryWake(conversationId)`.
- `private boolean tryPending(Long conversationId)`:
  1. 대기 행을 읽는다. 비었거나 멈춘 행이 있으면 거짓.
  2. 대화가 없거나 지워졌으면 거짓. 주인 사용자 행이 없으면 경고 로그를 남기고 거짓.
  3. `turns.open(owner.id(), conversationId)`. `CONVERSATION_BUSY` 면 참을 돌려준다. 도는 turn 이 닫힐 때 다시 온다.
  4. 가상 스레드 `pending-turn-<대화 번호>` 에서 `runQueuedTurn` 을 돌린다. 스레드를 띄우지 못하면 잠금을 닫는다. 참을 돌려준다.
- `private void runQueuedTurn(CurrentUser owner, Conversation conversation, TurnHandle handle, List<Long> ids)`: `chat.runPendingMessages(owner, conversation.id(), handle, event -> hub.publish(conversation.id(), event))` 를 부른다. `ApiException` 은 그 코드로, 그 밖의 `RuntimeException` 은 `INTERNAL_ERROR` 로 `ChatEvent.error` 를 낸다. 예외가 났고 `ids` 의 행이 하나라도 남아 있으면 `markHeld(conversationId, true)` 를 부르고 `pending` 사건을 낸다. `finally` 에서 `turns.close(handle)`.
- `dispatchAfterStartup()`: `pendingMessages.findConversationsReadyToSend()` 와 `wake.conversationsToWake()` 를 합쳐 겹치는 번호를 한 번만 돈다.

`CurrentUser` 는 `DelegationWakeService.tryWake` 와 같이 `AppUser` 에서 만든다.

### 9. `presentation/ChatDtos.java`

```java
/** 응답 중에 보내는 메시지다. 글만 받는다. */
public record PendingMessageRequest(@NotBlank @Size(max = 8000) String text) {}

public record PendingMessageView(Long id, String text, Instant createdAt) {}

/** @param held 멈춰 두었다. 사용자가 「보내기」 를 누를 때까지 보내지 않는다 */
public record PendingQueueView(boolean held, List<PendingMessageView> items) {}
```

`PendingQueueView` 에 `static PendingQueueView from(PendingQueue queue)` 를 둔다.

### 10. `presentation/PendingMessageController.java` 신규

`@RestController`, `@RequestMapping("/api/v1/chat/conversations/{conversationId}/pending")`, `@RequiredArgsConstructor`. 주입: `PendingMessageService`, `CurrentUserProvider`, `ConversationAccess`.

| 매핑 | 응답 |
| --- | --- |
| `@GetMapping` | 200 `PendingQueueView` |
| `@PostMapping`, `@Valid @RequestBody PendingMessageRequest` | 201 `PendingQueueView` |
| `@DeleteMapping("/{pendingId}")` | 204 |
| `@PostMapping("/send")` | 202 `PendingQueueView` |

`conversationId` 는 `UUID` 로 받고 `access.requireOwnId(user, conversationId)` 로 번호를 얻는다.

`presentation/ConversationEventController.java` 의 클래스 Javadoc 「여기에는 위임 결과로 열린 자동 turn 의 사건만 온다」 를 대기 메시지로 연 turn 의 사건과 `user`, `pending` 사건도 온다는 말로 고친다.

### 11. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/PendingMessageServiceTest.java`

`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(ChatServiceTest.StubRuntime.class)`, `@MockitoBean HermesRunEventStream eventStream` 으로 띄운다. 준비와 정리는 `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` 의 `setUp`, `awaitAllIdle`, `awaitIdle` 을 본보기로 쓴다. `@BeforeEach` 에서 대기 행도 비운다.
도는 turn 은 `StubHermesRunsClient.holdSubmits()` 와 `releaseSubmits()` 로 붙잡았다 푼다. 대기 메시지로 연 turn 은 다른 스레드에서 도므로 단언 전에 그 대화가 쉴 때까지 기다린다.

| 경우 | 기대 |
| --- | --- |
| turn 이 도는 동안 두 글을 더하고 turn 을 푼다 | 대역 Hermes 가 받은 둘째 명령의 입력이 두 글을 빈 줄로 이은 글로 끝난다. 메시지는 `USER`, `ASSISTANT`, `USER`(합친 글), `ASSISTANT` 넷이다. 대기 행이 없다. `auto_turn_count` 가 0 이다 |
| 위 경우에 대화를 `hub.subscribe` 로 듣는다 | `pending`, `user`, `started`, `done` 사건을 이 순서로 받는다. `user` 의 `text` 가 합친 글이다 |
| 도는 turn 이 없을 때 더한다 | 곧바로 turn 이 열리고 대기 행이 없다 |
| 대기 메시지 하나를 취소하고 turn 을 푼다 | 남은 글만 간다. 같은 번호를 다시 취소하면 `PENDING_MESSAGE_NOT_FOUND` |
| 여섯째를 더한다 | `PENDING_QUEUE_FULL`. 행은 다섯 |
| 합친 길이가 8000자를 넘게 더한다 | `PENDING_QUEUE_FULL` |
| 도는 turn 을 `chat.stop` 으로 중지한다 | 대기 행이 `held` 로 남고 새 turn 이 열리지 않는다. `release` 뒤에 합친 글이 간다 |
| 멈춘 대기 줄에 더한다 | 새 행도 `held` 다. turn 이 열리지 않는다 |
| 앞 turn 이 실패로 끝난다(`stub().willFail(...)` 뒤 다음 응답은 정상) | 대기 메시지가 간다 |
| 더한 뒤 에이전트를 끄고 turn 을 푼다 | 대기 행이 `held` 로 남고 `error` 사건이 `AGENT_DISABLED` 로 온다. turn 이 되풀이해 열리지 않는다(대역 Hermes 의 제출 수가 늘지 않는다) |
| 흐름이 붙은 에이전트의 대화에 더한다 | `CONVERSATION_BUSY`. 행이 없다 |
| 남의 대화에 더하거나 읽는다 | `CONVERSATION_NOT_FOUND` |
| 멈추지 않은 대기 행을 저장소로 직접 넣고 `dispatcher.dispatchAfterStartup()` 을 부른다 | 그 글이 간다. 멈춘 행만 있는 다른 대화는 그대로다 |
| 대기 행이 있는 대화를 `chat.delete` 로 지운다 | 그 대화의 대기 행이 없다 |

### 12. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/PendingBeforeDelegationTest.java`

`@SpringBootTest(properties = "assistant.delegation-wake.enabled=true")` 로 띄운다. 위임 결과 줄을 만드는 도우미(`delegated`, `finished`)는 `DelegationWakeServiceTest` 의 것을 본보기로 쓴다.

- 정상: 부모 turn 이 도는 동안 끝난 위임 결과 하나와 대기 메시지 하나가 함께 있다. turn 을 풀면 메시지가 `USER`(대기 글), `ASSISTANT`, `SYSTEM`(알림 줄), `ASSISTANT` 순서로 쌓인다.
- 실패 입력: 위와 같되 대기 줄이 멈춰 있다. 위임 결과의 자동 turn 은 열리고 대기 행은 `held` 로 남는다.

### 13. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/presentation/PendingMessageControllerTest.java`

`backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java` 의 띄우는 방식과 인증 방식을 따른다.

- 정상: POST 201 과 `items` 한 줄, GET 200, DELETE 204, `send` 202.
- 실패 입력: 빈 `text` 는 400 `VALIDATION_FAILED`, 8001자는 400, 남의 대화는 404 `CONVERSATION_NOT_FOUND`, 없는 `pendingId` 는 404 `PENDING_MESSAGE_NOT_FOUND`.

### 14. 개수를 상수로 단언하는 기존 검사

`git grep -n "ErrorCode.values()" -- backend/src/test` 와 `git grep -n "\"system\"" -- backend/src/test` 로 오류 코드 수나 사건 type 목록을 단언하는 검사가 있는지 본다. 있으면 같은 커밋에서 고치고 「변경 파일」 에 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*PendingMessageServiceTest' --tests '*PendingBeforeDelegationTest' --tests '*PendingMessageControllerTest'
cd backend && ./gradlew test
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

셋 다 `BUILD SUCCESSFUL` 이어야 한다. 둘째는 backend 전체 검사다. `ChatServiceTest`, `ChatStopTest`, `ChatRegenerateTest`, `DelegationWakeServiceTest` 가 그대로 통과해야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/PendingQueueChangedException.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/PendingQueue.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/PendingMessageService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/PendingMessageController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ConversationEventController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessageServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingBeforeDelegationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/presentation/PendingMessageControllerTest.java` | 신규 |
