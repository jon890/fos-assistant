# Phase 02. 다음 turn 을 정하는 자리를 하나로 모은다

**Execution profile**: standard

## 목표

turn 이 닫힐 때, 위임이 끝났을 때, 서버가 뜰 때 다음 turn 을 정하는 자리를 `NextTurnDispatcher` 하나로 모은다.
turn 이 중지로 닫혔는지를 종료 리스너가 알 수 있게 한다.
이 phase 에서는 동작이 바뀌지 않는다. 위임 결과 깨우기는 지금과 같이 돈다.

**범위 외**: 대기 메시지를 읽고 보내는 것(Phase 03). 이 phase 의 `NextTurnDispatcher` 는 위임 결과만 본다.

## 컨텍스트

지금 구조다. 모두 `backend/src/main/java/com/bifos/assistant/chat/application/` 아래다.

- `TurnCancellation.addCloseListener(Consumer<Long>)` 에 `DelegationWakeService` 가 생성자에서 `this::tryWake` 를 건다(`properties.enabled()` 일 때만).
- `TurnCancellation.close(TurnHandle)` 가 맵에서 뺀 뒤 `notifyClosed(conversationId)` 로 리스너를 부른다.
- `DelegationWakeService` 가 `@EventListener onDelegationFinished(DelegationFinished)` 와 `@EventListener(ApplicationReadyEvent.class) @Order(10) wakeAfterStartup()` 을 갖는다. `OrphanedExecutionSweeper` 는 `@Order(0)` 이다.
- `ChatService.runTurn` 은 취소된 turn 을 두 자리에서 돌려준다. 둘 다 `return recorded(cancel(pending, ...), startedAt);` 이다. `ChatService.runFlow` 는 `turn.cancelled()` 로 안다.
- `DelegationWakeService.tryWake(Long conversationId)` 는 `properties.enabled()` 가 거짓이면 아무것도 하지 않는다.

**근거 문서**: `docs/code-architecture.md` 의 「위임 결과로 부모 대화를 깨우기」 와 「응답 중 대기열」 절, `docs/adr/ADR-047-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md`

## 의도 메모

- 대기 메시지용 리스너를 하나 더 거는 안은 버렸다. 두 리스너가 같은 순간에 turn 잠금을 다투고 순서가 등록 순서에 달린다.
- `NextTurnDispatcher` 는 `assistant.delegation-wake.enabled` 와 무관하게 리스너를 건다. 그 설정은 `DelegationWakeService.tryWake` 안에서만 본다. Phase 03 의 대기 메시지는 테스트 profile 에서도 돌아야 한다.
- 중지 여부를 `handle.cancelled` 로 읽지 않는다. 중지 요청이 실패해 되돌린 turn 과 Hermes 가 스스로 `cancelled` 로 끝낸 turn 이 그 값과 어긋난다. `ChatService` 가 취소된 turn 을 돌려주는 자리에서 적는다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/application/TurnClosed.java` 신규

```java
/** turn 하나가 닫혔다. {@code stopped} 는 그 turn 이 중지로 끝났다는 뜻이다. */
public record TurnClosed(Long conversationId, boolean stopped) {}
```

### 2. `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java`

- `closeListeners` 의 타입을 `List<Consumer<TurnClosed>>` 로, `addCloseListener` 의 인자를 `Consumer<TurnClosed>` 로 바꾼다.
- `TurnHandle` 에 `private final AtomicBoolean stopped = new AtomicBoolean();` 를 더한다.
- `public void markStopped(TurnHandle handle)` 를 더한다. `handle.stopped.set(true)` 다.
- `close` 는 `notifyClosed(new TurnClosed(handle.conversationId, handle.stopped.get()))` 를 부른다. `notifyClosed` 의 인자와 로그를 맞춘다.

### 3. `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java`

- `runTurn` 의 취소 반환 두 자리 앞에서 `turns.markStopped(handle);` 를 부른다.
- `runFlow` 에서 `turn.cancelled()` 이면 `turns.markStopped(handle);` 를 부른다. `if (!turn.cancelled()) turns.markFinished(handle);` 줄 옆이다.

### 4. `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` 신규

`@Service` 다. 주입: `TurnCancellation turns`, `DelegationWakeService wake`.

- 생성자에서 `turns.addCloseListener(this::onTurnClosed);`
- `void onTurnClosed(TurnClosed closed)`: `tryNext(closed.conversationId())`.
- `@EventListener public void onDelegationFinished(DelegationFinished event)`: `event.conversationId()` 가 null 이 아니면 `tryNext`.
- `@EventListener(ApplicationReadyEvent.class) @Order(10) public void dispatchAfterStartup()`: `wake.conversationsToWake()` 의 대화마다 `tryNext` 를 부른다. 한 대화가 예외를 던져도 나머지를 돈다. 지금 `wakeAfterStartup` 의 try/catch 와 로그를 옮긴다.
- `public void tryNext(Long conversationId)`: `wake.tryWake(conversationId)`.

클래스 Javadoc 에 「다음 turn 을 정하는 자리는 이것 하나다(ADR-047)」 와 서버 한 대 전제를 적는다.

### 5. `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java`

- 생성자의 `turns.addCloseListener(this::tryWake)` 와 `onDelegationFinished`, `wakeAfterStartup` 을 지운다.
- `public List<Long> conversationsToWake()` 를 더한다. `properties.enabled()` 가 거짓이면 빈 목록, 참이면 `executions.findConversationsWithUndeliveredResults()` 다.
- 클래스 Javadoc 의 「잡지 못하면 그 turn 이 닫힐 때 다시 확인한다」 를 `NextTurnDispatcher` 가 다시 부른다는 말로 고친다.
- 쓰지 않게 된 import 를 지운다.

### 6. `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java`

- `wake.wakeAfterStartup()`(316줄 근처)을 주입받은 `NextTurnDispatcher` 의 `dispatchAfterStartup()` 으로 바꾼다.
- 이 파일의 다른 검사는 사건 발행과 turn 닫기로 깨우므로 그대로 통과해야 한다. 통과하지 않으면 리스너 연결이 틀린 것이다.

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java`

Spring 문맥 없이 `new TurnCancellation(mock(HermesRunsClient.class), Duration.ofSeconds(1))` 로 만든다. 끝에 `shutdown()` 을 부른다.

- 정상: `open` 뒤 `close` 하면 리스너가 `TurnClosed(conversationId, false)` 를 받는다.
- 정상: `open`, `markStopped`, `close` 하면 `stopped` 가 참이다.
- 실패 입력: 리스너가 예외를 던져도 `close` 가 예외 없이 끝나고 둘째 리스너가 불린다.
- 정상: 같은 handle 을 두 번 `close` 해도 리스너는 한 번만 불린다.

### 8. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java`

검사 하나를 더한다. `TurnCancellation.addCloseListener` 로 리스너를 걸어 받은 `TurnClosed` 를 모은다. 리스너는 지울 수 없으므로 그 대화 번호의 것만 모은다.

- 정상: 이 파일의 기존 방식으로 turn 을 중지하면 그 대화의 `TurnClosed.stopped` 가 참이다.
- 실패 입력: 중지하지 않고 끝난 turn 은 `stopped` 가 거짓이다.

## 검증

```bash
cd backend && ./gradlew test --tests '*TurnCancellationCloseTest' --tests '*DelegationWakeServiceTest' --tests '*ChatStopTest' --tests '*ChatServiceTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
git grep -n "addCloseListener" -- backend/src/main
```

- 앞의 둘은 `BUILD SUCCESSFUL` 이어야 한다.
- 마지막 명령은 `TurnCancellation.java` 의 정의와 `NextTurnDispatcher.java` 의 호출 한 줄만 보여야 한다. 테스트 코드의 호출은 이 명령이 보지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnClosed.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` | 수정 |
