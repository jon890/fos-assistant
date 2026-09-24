# Phase 01. Control Plane 이 돌고 있는 turn 을 자식 실행까지 멈춘다

**Execution profile**: deep

## 목표

`POST /api/v1/chat/executions/{id}/stop` 을 만든다.
뿌리 실행과 그 아래에서 도는 실행을 Hermes 에서 멈추고, 멈춘 turn 을 `CANCELLED` 로 남기고,
멈춘 자리까지의 답을 `chat_message` 에 저장하고, 스트림에 `stopped` 사건을 보낸다.

**범위 외**: 다시 생성과 수정은 phase 02 가 한다. 화면의 중지 단추는 phase 03 이 한다.
중지를 여러 Control Plane 프로세스 사이에서 나누는 것은 하지 않는다. 표시는 한 프로세스의 메모리에 둔다.

## 컨텍스트

이 phase 를 시작할 때 아래가 이미 코드에 있어야 한다. 화면 틀과 작업 과정을 만드는 앞선 작업이 넣는다.

- `ChatEvent` 의 `started` 사건. 실행 줄을 만들 때마다 `conversationId` 와 `executionId` 를 싣는다.
  provider 가 막혀 새 실행 줄로 다시 시도하면 새 번호로 다시 보낸다. 화면은 마지막으로 받은 번호로 중지를 보낸다
- `ChatEvent` 의 `tool` 과 `subagent` 사건, `MessageView.activity`

`backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` 에 `started` 를 만드는 정적 메서드가 없으면
`PHASE_BLOCKED: started 사건이 아직 없다` 를 출력하고 끝낸다.

사진 첨부가 이미 머지되어 `SendMessageRequest` 와 `ChatService` 의 모양이 이 문서와 다를 수 있다.
**구현 전에 `ChatService`, `ChatController`, `ChatDtos` 의 지금 모양을 읽는다.**
아래 이름은 이 문서를 쓸 때의 코드에서 확인한 것이다.

지금 코드의 모양이다.

| 무엇 | 어디 | 지금 하는 일 |
| --- | --- | --- |
| 한 모델로 한 번 도는 turn | `ChatService.runTurn` | 모델 순위마다 `begin` 으로 실행 줄을 만들고 `submit`, `relay`, `awaitCompletion` 을 부른다. `result.succeeded()` 가 아니고 `providerBlocked()` 도 아니면 `executions.fail` 뒤 `HERMES_RUN_FAILED` 를 던진다 |
| 스트림 중계 | `ChatService.relay` 와 `ChatService.forward` | `forward` 가 `delta` 를 `ChatEvent.delta` 로 흘린다. 모으지 않는다 |
| provider 전환 | `runTurn` 안 | 막히면 `ChatEvent.reset()` 을 보내고 새 실행 줄로 다시 시도한다. 새 줄은 `retryOfExecutionId` 로 앞 줄을 가리키고 `root_execution_id` 는 비어 있다 |
| 흐름으로 도는 turn | `ChatService.streamFlow` 와 `ResearchAndBuildFlow.run` | Chief 가 뿌리다. `AgentRunner.run` 이 Chief 를 끝까지 돌려 **뿌리 줄을 `SUCCEEDED` 로 바꾼 뒤** `runInParallel` 이 자식 둘을 띄우고, 이어서 `children.run` 으로 합치기를 돌린다 |
| 자식 한 번 | `ChildExecutionRunner.run` → `AgentRunner.run` | `executions.start` 뒤 `hermes.submit`, `executions.attachRunId`, `hermes.awaitCompletion`. 실패를 예외로 올리지 않고 `ChildResult.failed` 로 돌려준다 |
| 실행 줄 상태 | `ExecutionRecorder` | `start`, `attachRunId`, `complete`, `fail` 이 있다. **`CANCELLED` 로 옮기는 메서드가 없다.** `AgentExecution` 에도 `markSucceeded` 와 `markFailed` 만 있다 |
| 뿌리 아래 실행 찾기 | `AgentExecutionRepository.findByRootExecutionId(Long)` | 있다. 뿌리 자신은 빠진다 |
| Hermes 호출 | `HermesRunsClient` 와 `HttpHermesRunsClient` | `submit`, `awaitCompletion`, `readSessionRuntime`. `TERMINAL` 에 `cancelled` 가 이미 있어 멈춘 실행의 조회는 끝난 것으로 읽힌다 |
| 백엔드 테스트 대역 | `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` | `willReturn`, `beforeAwait`, `received` 가 있다 |
| e2e 대역 | `test/e2e/fake-hermes.ts` | `holdNextRun` 으로 실행을 `running` 에 붙잡아 둔다. `/v1/runs/{id}/stop` 은 없다 |

**흐름 경로에서는 뿌리 줄의 상태로 판정할 수 없다.** Chief 가 끝나면 뿌리 줄이 `SUCCEEDED` 가 되고 그 뒤에 자식이 돈다.
그래서 「돌고 있는가」 는 실행 줄의 상태가 아니라 이 프로세스가 그 turn 을 아직 돌리고 있는지로 본다.

**근거 문서**: `docs/adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md`, `docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md`, `docs/flow.md` 의 「중지할 때」 와 「실행이 실패할 때」, `docs/code-architecture.md` 의 「대화」 절 아래 「경로」 「화면으로 보내는 사건」 「중지」, `docs/data-schema.md` 의 「chat_message」, `docs/hermes-integration.md` 의 「취소가 아래로 내려가지 않는다」

## 의도 메모

- **판정을 실행 줄의 `RUNNING` 으로 하지 않는다.** 위 컨텍스트의 흐름 경로 때문이다. 도는 turn 의 목록으로 판정한다.
- 중지 표시를 `ChatService` 필드로 두지 않고 `chat/application` 의 빈 하나로 뺀다.
  `AgentRunner` 와 `ResearchAndBuildFlow` 도 그것을 봐야 하는데 `ChatService` 를 주입하면 순환한다.
  `orchestration` 은 이미 `chat.application` 의 `ChatEvent` 와 `ChatTurn` 을 쓰므로 방향이 새로 생기지 않는다.
- Hermes 에 중지를 보내는 순서가 경쟁을 만든다. `started` 를 보낸 뒤 제출하기 전에 중지가 오면 멈출 run 이 아직 없다.
  **그래서 run 을 등록하는 `trackRun` 이 등록한 직후 표시를 보고, 세워져 있으면 그 자리에서 그 run 에 중지를 보낸다.**
  이 규칙은 `TurnCancellation` 한 곳에만 둔다. 뿌리 경로와 흐름의 Chief 와 자식이 모두 제출 직후 `trackRun` 을 부른다.
- 중지를 보냈는지는 run 마다 적는다. 보내다 실패한 run 은 다음 중지 요청이 다시 보낸다. 성공한 run 에는 다시 보내지 않는다.
- 취소된 run 의 스트림이 닫히는지 실제 Hermes 로 확인하지 못했다. 닫히지 않으면 `HermesRunEventStream.open` 이 `runTimeout` 까지 읽는다.
  그래서 중지를 보낸 뒤 10초 안에 중계가 끝나지 않으면 Control Plane 이 스트림을 스스로 닫는다.
- 멈춘 실행의 `output` 을 먼저 쓰고 비어 있으면 모은 조각을 쓴다. 이 순서는 ADR-021 이 정했다.
- 멈춘 실행의 토큰은 버리지 않는다. Hermes 가 `usage` 를 주면 `SUCCEEDED` 와 같은 방식으로 환산해 적는다. 돈은 이미 나갔다.
- 흐름이 멈추면 뿌리 줄을 `CANCELLED` 로 바꾼다. Chief 가 이미 `SUCCEEDED` 로 적었더라도 turn 전체가 멈춘 것이 사용자에게 보이는 사실이다.
  Chief 의 토큰과 금액은 그대로 둔다.

## 작업 항목

### 1. `hermes/HermesRunsClient.java` 와 `hermes/HttpHermesRunsClient.java` 에 중지를 더한다

`HermesRunsClient` 에 메서드 하나를 더한다.

```java
/** 그 실행을 멈추라고 보낸다. Hermes 는 곧바로 stopping 을 주고, 조회하면 곧 cancelled 가 된다. */
void stop(String apiBaseUrl, String profileName, String runId);
```

`HttpHermesRunsClient.stop` 은 `POST {apiBaseUrl}/v1/runs/{runId}/stop` 을 부른다.
key 는 `submit` 과 같은 방법으로 `HermesProfileKeyStore` 에서 profile 이름으로 꺼내고, 요청 형태는 `fetch` 와 같은 `RestClient` 호출을 따른다.
404 는 이미 끝나 사라진 실행이므로 무시하고 로그 한 줄만 남긴다. 그 밖의 실패는 `HERMES_UNAVAILABLE` 로 올린다.

`StubHermesRunsClient` 에 `stop` 을 구현한다. 받은 run 번호를 목록에 쌓고, 테스트가 `stopped()` 로 읽는다.
`beforeAwait` 처럼 테스트가 끼울 수 있는 `onStop(Consumer<String>)` 을 둔다.

### 2. `chat/application/TurnCancellation.java` 를 새로 만든다

도는 turn 을 추적하고 중지 표시를 갖는 `@Component` 다.

| 메서드 | 하는 일 |
| --- | --- |
| `TurnHandle open(Long userId, Long conversationId)` | turn 을 등록한다. **사용자 메시지를 저장하기 전에** 부른다. 같은 대화에 도는 turn 이 이미 있으면 등록하지 않고 `CONVERSATION_BUSY` 를 던진다. 이때 실행 번호는 아직 없다 |
| `void rekey(TurnHandle handle, Long executionId)` | 실행 줄을 만들 때마다 부른다. 첫 줄도 여기서 넣는다. `started` 를 보내기 직전이다. provider 전환으로 새 줄이 생기면 turn 을 새 번호로 옮기고 옛 번호로는 더 찾지 못한다. 옛 줄은 이미 `FAILED` 다 |
| `Optional<TurnHandle> find(Long executionId)` | 실행 번호로 turn 을 찾는다 |
| `void close(TurnHandle handle)` | turn 이 어떻게 끝나든 `finally` 에서 부른다 |
| `boolean isCancelled(Long rootExecutionId)` | 흐름이 본다. 등록되지 않은 번호는 거짓이다 |
| `void trackRun(Long rootExecutionId, String apiBaseUrl, String profileName, String runId)` | 제출한 run 을 그 turn 에 더한다. **더한 직후 표시가 서 있으면 그 자리에서 `hermes.stop` 을 보낸다** |
| `List<RunRef> pendingStops(TurnHandle handle)` | 아직 중지를 보내지 않았거나 보내다 실패한 run 들 |
| `void markStopSent(RunRef run)` | 그 run 에 중지를 보냈다고 적는다 |
| `void attachStream(TurnHandle handle, Closeable stream)` / `detachStream(TurnHandle handle)` | 지금 읽고 있는 스트림을 걸어 둔다. 중지 뒤 10초 안에 끝나지 않으면 닫기 위해서다 |
| `boolean busy(Long conversationId)` | 그 대화에 도는 turn 이 있는지 |

`TurnHandle` 은 `userId`, `conversationId`, 지금 실행 번호, 세워졌는지(`AtomicBoolean cancelled`), 지금 돌고 있는 run 들, 걸어 둔 스트림을 가진다.
run 은 `RunRef(apiBaseUrl, profileName, runId)` 와 그 run 에 중지를 보냈는지(`AtomicBoolean stopSent`)다.
뿌리 경로와 `AgentRunner` 가 `submit` 직후 `trackRun` 으로 더한다.

**`open` 은 대화 확인과 등록을 한 번에 한다.** 대화 번호마다 turn 하나를 `ConcurrentHashMap<Long, TurnHandle>` 의 `putIfAbsent` 로 넣고,
이미 있으면 `CONVERSATION_BUSY` 를 던진다. 확인과 등록 사이에 다시 생성 둘이 함께 들어와 같은 답을 가리키는 판이 둘 생기지 않게 한다.

`TurnCancellation` 은 `HermesRunsClient` 를 주입받아 `trackRun` 안에서 중지를 보낸다. `trackRun` 이 보낸 중지가 실패하면 `stopSent` 를 세우지 않고 로그만 남긴다.
그 run 은 다음 중지 요청의 `pendingStops` 에 다시 나온다.
`rekey` 는 앞 시도의 run 을 목록에서 지운다. 앞 시도는 끝난 실행이라 멈출 것이 없다.
`isCancelled` 는 turn 의 지금 번호로 찾는다. 흐름은 Chief 가 뿌리이고 전환하지 않으므로 뿌리 번호가 곧 지금 번호다.
저장소는 실행 번호로 찾는 `ConcurrentHashMap<Long, TurnHandle>` 과 대화 번호로 찾는 것 둘이다. 잠금 없이 여러 스레드가 읽고 쓴다.
`close` 는 두 곳에서 모두 뺀다.

### 3. `chat/application/ChatService.java` 에 `stop` 과 조각 모으기를 더한다

`public void stop(CurrentUser user, Long executionId)` 를 더한다.

1. `turns.find(executionId)` 가 비어 있으면 `AgentExecutionRepository.findById` 로 줄을 찾는다.
   줄이 없거나 `userId` 가 요청자가 아니면 `EXECUTION_NOT_FOUND`, 있으면 `EXECUTION_NOT_RUNNING` 을 던진다.
2. 찾은 handle 의 `userId` 가 요청자가 아니면 `EXECUTION_NOT_FOUND` 를 던진다. 남의 turn 이 있는지 알려 주지 않는다.
3. 표시를 세운다. 이미 서 있어도 다음으로 간다. 앞 요청에서 보내지 못한 run 이 있을 수 있다.
4. `turns.pendingStops(handle)` 의 run 마다 `hermes.stop` 을 부르고, 성공하면 `markStopSent` 한다.
   이미 보낸 run 에는 다시 보내지 않는다. 두 번 눌러도 성공한 run 에 두 번 가지 않는다.
5. 흐름의 자식이 `trackRun` 을 부르기 전에 이미 실행 줄과 `hermesRunId` 를 적었을 수 있다.
   `findByRootExecutionId(rootExecutionId)` 로 찾은 줄 가운데 `status == RUNNING` 이고 `hermesRunId` 가 있는데 handle 에 없는 것을 `trackRun` 으로 더한다.
   `apiBaseUrl` 은 그 줄의 `agentId` 로 `AgentService.requireById` 해서 얻고 profile 은 줄의 `profileName` 이다.
   `trackRun` 이 표시를 보고 곧바로 보낸다.
6. Hermes 호출 하나가 실패해도 나머지를 계속 보낸다. 하나라도 실패했으면 모두 보낸 뒤 `HERMES_UNAVAILABLE` 을 던진다.
   화면은 이 오류를 받으면 중지 단추를 다시 풀고, 다시 누르면 3번부터 다시 돈다.
7. 표시를 처음 세운 요청이면 10초 뒤 `attachStream` 으로 걸린 스트림이 아직 있을 때 그것을 닫는 일을 예약한다.
   `ScheduledExecutorService` 하나를 `TurnCancellation` 이 갖는다. 닫는 것은 `Closeable.close()` 다.

**스트림을 닫는 방법.** `HermesRunEventStream.open` 은 `try (InputStream body = ...)` 안에서 `readEvents` 가 `readLine` 을 돈다.
밖에서 끊으려면 그 `InputStream` 에 닿아야 한다. `open` 에 인자 하나를 더한 판을 둔다.

```java
public void open(String apiBaseUrl, String profileName, String runId,
        Consumer<RunEvent> onEvent, Consumer<Closeable> onOpened)
```

`body` 를 얻은 직후 `onOpened.accept(body)` 를 부른다. 기존 4인자 `open` 은 `stream -> {}` 를 넘기는 위임으로 남긴다.
다른 스레드가 `body.close()` 를 부르면 `readLine` 이 `IOException` 으로 끝나고 `open` 은 `HERMES_UNAVAILABLE` 로 올린다.
`ChatService.relay` 는 이미 그 `ApiException` 을 잡아 로그만 남기고 `awaitCompletion` 으로 넘어간다. 그 자리를 고치지 않는다.
`relay` 는 `onOpened` 에서 `turns.attachStream` 을, `finally` 에서 `turns.detachStream` 을 부른다.

`runTurn` 과 `streamFlow` 를 이렇게 고친다.

- `runTurn` 이 `messages.save(ChatMessage.fromUser(...))` 를 하기 전에 `turns.open(user.id(), conversation.id())` 를 부르고, 메서드 끝의 `finally` 에서 `close` 한다.
  `CONVERSATION_BUSY` 로 거절되면 사용자 메시지도 실행 줄도 남지 않는다. 흐름 경로(`streamFlow`)도 흐름을 부르기 전에 같은 자리에서 `open` 한다.
- 실행 줄을 만들 때마다 `turns.rekey(handle, execution.id())` 를 부른다. 첫 줄도 같다.
  `started` 는 실행 줄을 만들 때마다 이미 보내고 있다. 그 자리 바로 앞에서 `rekey` 를 부른다. 화면이 번호를 받기 전에 turn 이 그 번호로 찾아져야 한다.
- 이 등록으로 **같은 대화에 보통 보내기가 겹쳐도 `CONVERSATION_BUSY` 가 된다.** `docs/flow.md` 「대화 이력」 의 「앞의 요청이 끝나기 전에는 두 번째를 보내지 않는다」 를 서버도 지키게 되는 것이다.
  `ErrorCode` 에 `CONVERSATION_BUSY(HttpStatus.CONFLICT)` 를 이 phase 에서 더한다. 화면 문구는 phase 02 가 더한다.
- **등록과 해제는 `ChatService` 만 한다.** `streaming` 이 거짓인 한 번에 받는 경로도, 흐름 경로도 등록한다. `open` 과 `close` 를 `if (streaming)` 안에 두지 않는다.
  등록되지 않은 turn 은 중지도 `CONVERSATION_BUSY` 도 받지 못한다. 흐름 경로의 뿌리 번호는 아래 5번의 `onStarted` 에서 `rekey` 로 넣는다.
- `submit` 이 run 번호를 돌려준 직후 `turns.trackRun(root, apiBaseUrl, profileName, runId)` 를 부른다. 그 안에서 표시를 본다.
- 전환 뒤에 멈춤 표시가 이미 세워져 있으면 새 시도를 제출하지 않고 멈춘 turn 으로 끝낸다. 남길 글은 새 시도의 `streamed` 기준이라 비어 있고 메시지를 만들지 않는다.
- `PendingTurn` 에 `StringBuilder streamed` 를 더한다. `forward` 가 `delta` 를 흘릴 때 같은 글을 거기 붙인다.
  `reset` 을 보낼 때 그 turn 의 `streamed` 도 비운다. 전환하면 새 `PendingTurn` 이 새 `StringBuilder` 를 갖는다.
- `awaitCompletion` 이 돌려준 결과의 `status` 가 `cancelled` 이거나 handle 이 세워져 있으면 새 메서드 `cancel(pending, result, option)` 로 간다.
  `providerBlocked` 판정보다 먼저 본다. 멈춘 실행을 막힌 provider 로 읽어 다음 모델로 넘어가면 안 된다.
- `cancel` 은 아래를 한다.
  1. `executions.cancel(...)` 로 줄을 `CANCELLED` 로 바꾼다. 작업 항목 4 가 만든다.
  2. `ExecutionEventType` 에 `RUN_CANCELLED` 를 더하고 `append(pending, ExecutionEventType.RUN_CANCELLED, null)` 로 남긴다. `RUN_FAILED` 를 남기지 않는다.
     `ExecutionEventRecorder` 는 이 값을 Hermes 사건에서 옮기지 않으므로 그 표를 고치지 않는다.
     `web/src/components/execution/execution-tree.tsx` 의 `ExecutionEventType` 에도 같은 값을 더하고, 실행 나무는 그 줄을 「중지됨」 으로 그린다.
  3. 남길 글을 정한다. `result.output()` 이 비어 있지 않으면 그것, 아니면 `streamed` 다. 둘 다 비면 메시지를 만들지 않는다.
  4. 글이 있으면 `ChatMessage.fromAssistant(conversationId, text, executionId)` 로 저장한다. `hermesSessionId` 가 오면 `rememberSession` 한다.
  5. `memoryProposer.proposeFrom` 은 부르지 않는다. 사람이 끊은 답에서 기억을 제안하지 않는다.
- `ChatTurn` 에 `boolean cancelled` 를 더한다. 기존 생성 자리는 `false` 를 넘긴다.
- `stream` 과 `streamFlow` 는 `turn.cancelled()` 이면 `done` 대신 `ChatEvent.stopped(conversationId, messageId, executionId)` 를 보낸다. `messageId` 는 null 일 수 있다.
  `streamFlow` 는 멈췄으면 `delta` 로 답을 흘리지 않는다.
- 한 번에 받는 경로 `send` 도 같은 `cancel` 을 탄다. 응답은 지금 모양을 그대로 쓰고 `assistantText` 에 남긴 글을 싣는다.

### 4. `usage/domain/AgentExecution.java` 와 `usage/application/ExecutionRecorder.java` 에 취소를 더한다

- `AgentExecution.markCancelled(String provider, String model, TokenUsage usage, ExecutionCost cost, Instant finishedAt)` 를 더한다.
  토큰과 금액은 `markSucceeded` 의 `ExecutionCost` 판과 같이 채우고 상태만 `CANCELLED` 로 둔다.
- `AgentExecution.markCancelled(Instant finishedAt)` 도 더한다. 흐름의 뿌리처럼 토큰을 이미 적은 줄의 상태만 바꾼다.
- `ExecutionRecorder.cancel(AgentExecution, Agent, HermesRunResult, ModelOption)` 는 `complete` 와 같은 순서로 모델과 금액을 정하고 `markCancelled` 를 부른다.
  `result` 가 null 이면 토큰 없이 상태만 바꾼다.
- `ExecutionRecorder.cancel(AgentExecution)` 은 상태만 바꾼다.

### 5. 흐름이 멈춤을 본다

`orchestration/application/ResearchAndBuildFlow.java`

- `run` 에서 Chief 가 끝난 뒤, `runInParallel` 을 부르기 전, 합치기를 부르기 전에 `cancellation.isCancelled(root.id())` 를 본다.
  세워져 있으면 뿌리 줄에 `executions.cancel(root)` 를 부르고 `new ChatTurn(conversation.id(), root.id(), "", null, true)` 를 돌려준다.
  단계 사건은 도는 중이던 단계에 `failed` 를 보내지 않고 그대로 둔다. 화면은 `stopped` 를 받으면 끝나지 않은 줄을 「중지됨」 으로 그린다.
- 자식이 멈춰 `ChildResult.failed` 로 돌아온 것과 실제 실패를 구분한다. 표시가 세워져 있으면 실패로 읽지 않고 위와 같이 멈춘 turn 으로 끝낸다.

`orchestration/application/AgentRunner.java`

`AgentRunner.run` 에는 화면 틀을 만든 앞선 작업이 넣은 8인자 판이 이미 있다. 마지막 인자가 `Consumer<AgentExecution> onStarted` 이고 실행 줄을 만든 직후 불린다.
**그 서명을 바꾸지 않는다.** 인자 하나를 더한 9인자 판을 두고 8인자 판은 그것에 위임한다.

```java
public Run run(CurrentUser user, Conversation conversation, Agent agent, String task,
        Long parentExecutionId, Long rootExecutionId, String sessionId,
        Consumer<AgentExecution> onStarted,
        BiConsumer<AgentExecution, String> onSubmitted)
```

- `onSubmitted` 는 `hermes.submit` 과 `executions.attachRunId` 를 한 직후 `(execution, runId)` 로 불린다. 8인자 판은 `(execution, runId) -> {}` 를 넘긴다.
- 흐름의 Chief 호출은 `onStarted` 에서 `ChatService` 가 넘긴 콜백을 부른다. 그 콜백이 `turns.rekey` 와 `started` 를 한다. 흐름은 `open` 하지 않는다.
  `onSubmitted` 에서 `turns.trackRun(execution.id(), agent.apiBaseUrl(), agent.hermesProfile(), runId)` 를 부른다.
  Chief 는 `rootExecutionId` 가 null 이라 이 콜백 말고는 run 을 알릴 자리가 없다. 이 콜백이 없으면 Chief 가 도는 동안 중지해도 Hermes 에 가지 않는다.
- `ChildExecutionRunner` 가 부르는 자식 실행도 `onSubmitted` 로 `turns.trackRun(rootExecutionId, ...)` 를 부르게 한다. `ChildExecutionRunner.run` 에 같은 인자를 더하고 흐름이 넘긴다.
- `awaitCompletion` 이 `cancelled` 를 주면 `executions.fail` 대신 `executions.cancel(execution, agent, result, option)` 을 부르고 `ChildResult.failed(id, "CANCELLED")` 를 돌려준다.
- `ResearchAndBuildFlow` 는 `TurnCancellation` 을 주입받아 `isCancelled` 와 `trackRun` 을 부른다. `open` 과 `close` 는 부르지 않는다.
  `ResearchAndBuildFlowTest` 가 흐름을 직접 부를 때는 테스트가 `turns.open` 을 먼저 부르고 그 handle 을 넘긴다.
흐름에 handle 을 어떻게 넘기는지는 `Flow.run` 의 지금 서명을 읽고 정한다. 인자 하나를 더하는 것이 가장 작다. phase 02 도 `Flow.run` 서명을 바꾸므로 두 변경을 한 인자로 모은다.

### 6. `chat/presentation/ChatController.java` 에 경로를 더한다

```java
@PostMapping("/executions/{executionId}/stop")
@ResponseStatus(HttpStatus.ACCEPTED)
public StopResponse stop(@PathVariable Long executionId)
```

`ChatDtos.StopResponse(String status)` 를 더하고 `"stopping"` 을 싣는다.
`ErrorCode` 에 `EXECUTION_NOT_RUNNING(HttpStatus.CONFLICT)` 를 더한다. `CONVERSATION_BUSY` 는 작업 항목 3 에서 더했다.

`ChatEvent` 에 `stopped(Long conversationId, Long messageId, Long executionId)` 를 더한다. `type` 은 `"stopped"` 다.
`ChatEvent` 의 칸 목록이 이미 길다. 새 칸을 만들지 않고 `done` 이 쓰는 세 칸을 그대로 쓴다.

### 7. `MessageView.status` 를 싣는다

`ChatController.messages` 가 만드는 `MessageView` 에 `String status` 를 더한다.
답 메시지는 그 `executionId` 의 실행 상태 이름을, 사용자 메시지는 null 을 싣는다.
실행마다 읽지 않는다. `ChatService` 에 `Map<Long, ExecutionStatus> statuses(List<ChatMessage>)` 를 두고 `executionIdsHavingChildren` 처럼 번호 목록으로 한 번에 읽는다.
목록이 비면 부르지 않는다.

### 8. web 서버 라우트를 더한다

`web/src/app/api/chat/executions/[id]/stop/route.ts` 를 만든다.
형태는 `web/src/app/api/memories/[id]/accept/route.ts` 를 따른다. 그 파일은 `@/lib/control-plane` 의 `callControlPlane` 을 부르고,
`id` 를 `/^\d+$/` 로 검사하고, 성공이면 `NextResponse.json(result.data)` 로 200 을 준다.
여기서는 성공일 때 `NextResponse.json(result.data, { status: 202 })` 로 202 를 그대로 넘긴다.
실패는 그 파일처럼 `{ code, message }` 와 `result.status` 를 넘긴다.
구현 전에 `web/src/lib/control-plane.ts` 를 열어 `callControlPlane` 의 결과 모양(`ok`, `data`, `status`, `code`, `message`)이 지금도 같은지 확인한다.

`web/src/components/error-message.ts` 에 `EXECUTION_NOT_RUNNING` 문구를 더한다. 「이미 끝난 답이다.」

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` 를 새로 만든다. 기존 `ChatServiceTest` 의 설정과 `StubHermesRunsClient` 를 따른다.

| 경우 | 기대 |
| --- | --- |
| 도는 turn 을 멈춘다. 대역이 `beforeAwait` 에서 `stop` 을 부르고 `cancelled` 와 `output` "절반" 을 돌려준다 | 줄이 `CANCELLED`. 답 메시지 "절반". `stopped()` 에 그 run 번호. 사건에 `RUN_CANCELLED` |
| `output` 이 비어 오고 `delta` 로 "앞부분" 이 흘렀다 | 답 메시지 "앞부분" |
| `output` 도 조각도 없다 | 답 메시지가 없다. 스트림 마지막 사건이 `stopped` 이고 `messageId` 가 null |
| 전환으로 `reset` 뒤에 멈췄다 | 앞 시도의 조각이 답에 섞이지 않는다. 중지는 새 시도의 run 에만 간다 |
| 전환 뒤에 앞 시도의 번호로 멈춘다 | 앞 줄은 `FAILED` 라 `EXECUTION_NOT_RUNNING`. 새 시도는 계속 돈다 |
| 남의 실행 번호 | `EXECUTION_NOT_FOUND` |
| 끝난 실행 번호 | `EXECUTION_NOT_RUNNING` |
| 두 번 누른다 | 첫 중지가 성공했으면 Hermes 에 한 번만 간다 |
| 첫 중지가 실패하고 다시 누른다. 대역의 `onStop` 이 첫 호출에만 예외를 던진다 | 첫 요청은 `HERMES_UNAVAILABLE`. 둘째 요청이 같은 run 에 다시 보내고 `stopped()` 에 그 번호가 있다 |
| `started` 뒤, 제출 전에 멈춘다. `turns.open` 뒤 `submit` 전에 테스트가 `stop` 을 부른다 | `trackRun` 이 곧바로 그 run 에 중지를 보낸다. 줄이 `CANCELLED` |
| 중지 뒤 스트림이 닫히지 않는다 | 10초 안에 스트림이 닫히고 상태 조회로 넘어가 `CANCELLED`. 테스트는 예약 시간을 설정으로 줄여 돌린다 |
| 한 번에 받는 경로의 turn 을 멈춘다 | 등록돼 있어 멈춘다. `EXECUTION_NOT_RUNNING` 이 아니다 |
| 같은 대화에 turn 이 도는 중에 두 번째 turn 을 연다 | `open` 이 `CONVERSATION_BUSY` 를 던진다 |

`backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` 에 둘을 더한다.
자식 둘이 도는 중에 멈추면 두 run 모두 `stopped()` 에 있고 합치기가 불리지 않는다.
Chief 가 도는 중에 멈추면 **Chief 의 run 번호가 `stopped()` 에 있고** 자식을 시작하지 않는다.
자식을 시작하지 않은 것만 보면 Chief 가 끝까지 돈 경우도 통과하므로 run 번호를 함께 본다.

10초 예약 시간은 `assistant.chat.stop-stream-grace` 같은 설정 하나로 두고 기본값을 10초로 한다. 이름은 기존 `application.yml` 의 `assistant.` 아래 이름 짓는 방식을 따른다.

`test/e2e/fake-hermes.ts` 에 `POST /p/{profile}/v1/runs/{id}/stop` 을 더한다. 경로 모양은 `RUN_EVENTS_PATH` 처럼 정규식 하나로 둔다.
그 run 의 `status` 를 `cancelled` 로 바꾸고 `{ "status": "stopping" }` 을 준다. 받은 run 번호를 `FakeHermesControl` 에서 읽을 수 있게 목록에 쌓는다.
**붙잡아 둔 run 이면 붙잡음을 풀되 `releaseHeldRun` 을 부르지 않는다.** 그 함수는 run 의 `status` 를 `completed` 로 덮어 `cancelled` 가 사라진다.
`heldRunId`, `heldRunReady`, `heldRunWaiter` 를 비우는 부분만 따로 떼어 쓴다.

지금 대역은 붙잡은 run 에서도 사건 스트림을 곧바로 닫고 `output` 을 늘 채운다. 실제 Hermes 가 그렇다는 근거는 없다.
입력 글로 고르는 모드 둘을 더한다. `specialOutputFor` 와 `interruptEvents` 가 입력으로 모드를 고르는 방식을 따른다.

| 입력 | 대역이 하는 것 |
| --- | --- |
| `중지 스트림 유지 검사` | 사건 스트림을 닫지 않는다. 첫 조각만 보내고 `stop` 을 받을 때까지 연결을 둔다. `stop` 을 받아도 닫지 않는다 |
| `중지 빈 답 검사` | `stop` 을 받으면 `status` 는 `cancelled`, `output` 은 빈 글이 된다 |
`test/e2e/scenarios/stop.ts` 를 새로 만들고 `test/e2e/run.ts` 의 `SCENARIOS` 에서 `streamingScenario` 뒤에 둔다.
`holdNextRun` 으로 붙잡고 `waitForHeldRun` 으로 제출을 기다린 뒤 중지를 부른다. 202, 스트림의 `stopped`, 실행 목록의 `CANCELLED`, 대역이 받은 stop 목록에 그 run 이 있는지를 본다.
남의 사용자로 같은 번호를 멈추면 404 다.
`중지 스트림 유지 검사` 로 보내고 멈추면 10초 안에 `stopped` 가 온다. 오지 않으면 실패다.
`중지 빈 답 검사` 로 보내고 멈추면 흐른 첫 조각이 답 메시지로 남는다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*ChatStopTest' --tests '*ResearchAndBuildFlowTest'
./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -rn 'EXECUTION_NOT_RUNNING' backend/src/main web/src
```

마지막 줄이 `ErrorCode`, `ChatService`, `error-message.ts` 를 모두 내야 한다.
AGENTS.md 의 「확인」 절 순서를 지킨다. `gradlew test` 를 `test/e2e` 보다 먼저 돌린다.

끝나면 `tasks/plan021-stop-regenerate/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurn.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEventType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/Flow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `web/src/app/api/chat/executions/[id]/stop/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/execution/execution-tree.tsx` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/stop.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
