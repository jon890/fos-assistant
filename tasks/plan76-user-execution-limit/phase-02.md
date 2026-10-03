# Phase 02. 한도에 닿았을 때 경로마다 거절하는 방법

**Execution profile**: deep

## 목표

phase 01 의 `USER_BUSY` 가 나올 수 있는 모든 경로가 기다리지 않고 정해진 방법으로 끝나게 한다.
보내기와 다시 생성은 409 로 거절되고, 대기 메시지 turn 은 대기 줄을 멈추고, 위임 결과 자동 turn 은 상한이 있는 재시도를 걸고, 위임 자식은 `BUSY` 를 받고, 흐름은 `USER_BUSY` 로 끝나고, 추천 질문과 Memory 제안은 건너뛴다.

**범위 외**: 제한기와 turn 자리 자체(phase 01), 원격 종료 확인 자리(phase 03), 화면 문구와 측정(phase 04).

## 컨텍스트

**근거 문서**: `docs/backend/execution-limit.md` 의 「한도에 닿을 때」 표, `docs/backend/turn-control.md` 의 「응답 중 대기열」 절, `docs/backend/agent-delegation.md` 의 「위임이 갈리는 지점」 표, `docs/adr/ADR-069-사용자-전체-실행-한도는-turn-자리와-실행-줄을-사용자-잠금-하나에서-센다.md`.

phase 01 이 끝난 상태:
- `TurnCancellation.open` 이 대화 잠금을 잡은 뒤 turn 자리를 얻고, 없으면 잠금을 되돌리고 `ApiException(ErrorCode.USER_BUSY)` 를 던진다.
- `ExecutionRecorder.start`, `startInheriting`, `startDetached` 가 자식과 백그라운드 줄을 만들기 전에 판정하고, 닿으면 줄을 만들지 않고 `ApiException(ErrorCode.USER_BUSY)` 를 던진다.
- 테스트 profile 의 `assistant.user-execution.max-running` 은 1000 이다. 한도를 보는 검사는 `@SpringBootTest(properties = "assistant.user-execution.max-running=...")` 로 작은 값을 준다.

경로별 지금 코드:
- 보내기와 다시 생성: `chat/application/ChatService.java` 의 `stream`(약 150행), `runTurn`(약 443행), `runFlow`(약 551행), `regenerate`(약 861행)가 질문을 저장하기 전에 `turns.open` 을 부른다. 스트림 경로의 예외는 `chat/presentation/ChatEventStreams.java` 가 `ChatEvent.error(code, message)` 로 보낸다. 스트림이 아닌 경로는 `shared/error/GlobalExceptionHandler` 가 `ErrorResponse(code, message)` 와 그 코드의 HTTP 상태로 답한다. 그래서 이 두 경로는 코드를 바꾸지 않아도 `USER_BUSY` 를 돌려준다. 검사로 확인한다.
- 대기 메시지 turn: `chat/application/NextTurnDispatcher.java` 의 `tryPending` 이 `turns.open(owner.id(), conversationId)` 에서 `CONVERSATION_BUSY` 만 잡고 나머지는 다시 던진다. 같은 파일의 `holdUnsent(Conversation, List<Long>)` 가 대기 행을 멈추고, `hub.publish(conversationId, ChatEvent.error(...))` 가 대화 단위 SSE 로 오류를 보낸다.
- 위임 결과 자동 turn: `chat/application/DelegationWakeService.java` 의 `tryWake` 가 `turns.open` 에서 `CONVERSATION_BUSY` 만 잡는다. 실패 유예는 `FAILURE_BACKOFF`(30초)와 `lastFailures` 맵이 갖는다. 대화를 다시 부르는 자리는 `NextTurnDispatcher.tryNext` 하나이고, `NextTurnDispatcher` 가 `DelegationWakeService` 를 부르는 방향이다. 거꾸로 부르지 않는다. 위임 종료는 Spring 사건 `DelegationFinished` 를 `NextTurnDispatcher.onDelegationFinished` 가 받는 방식이다.
- 위임 자식: `orchestration/application/AgentDelegationService.java` 의 `delegate` 가 실행 스레드의 `run` 을 띄우고 `Handoff` 로 결과를 받는다. 줄을 만들기 전에 실패하면 `handoff.rowCreated()` 가 거짓이고 `handoff.failure()` 에 예외가 있다. 지금은 `DataIntegrityViolationException` 말고는 모두 `SUBMIT_FAILED` 다. `run` 은 `RuntimeException` 을 `warn` 으로 남긴다. 실패 코드는 `orchestration/application/DelegationResult.java` 의 `Failure` 이고, 모델에 가는 문구는 `mcp/application/McpToolService.java` 의 `BUSY -> "지금은 맡길 수 없습니다. 잠시 뒤 다시 시도해 주세요."`(약 298행)다.
- 흐름: `orchestration/application/ResearchAndBuildFlow.java` 의 `runChild` 가 `children.run(...)` 을 부른다. `AgentRunner.run` 은 `executions.start` 의 예외를 그대로 올린다. `runInParallel` 은 `CompletableFuture.join` 으로 모아 예외가 `CompletionException` 으로 번진다. 단계 실패는 `stop(root, errorCode)` 가 루트 줄을 실패로 적고 `ApiException(ORCHESTRATION_STEP_FAILED)` 를 돌려준다. 결과 타입은 `orchestration/domain/ChildResult` 의 `failed(Long executionId, String errorCode)` 다.
- 추천 질문: `chat/application/StarterSuggestionService.java` 의 `generate` 가 `executions.startDetached` 를 try 안에서 부르고, 예외면 `lastFailures.put(key, ...)` 로 재시도를 막고 `warn` 을 남긴다.
- Memory 제안: `memory/application/MemoryProposer.java` 의 `proposeFrom` 이 `executions.startInheriting` 을 try 안에서 부르고 예외면 `warn` 을 남긴다.

## 의도 메모

- 대기열을 만들지 않는다. 대기 메시지 turn 이 한도에 닿으면 대기 행을 멈춘다. 그대로 두면 이 대화의 turn 이 닫힐 때까지 보낼 계기가 없고, 사용자의 다른 대화가 끝나도 이 대화의 `tryNext` 는 불리지 않는다.
- 위임 결과 자동 turn 은 결과를 잃지 않게 다시 시도하되 무한히 하지 않는다(코디네이터 요구). 연속 10번까지만 `FAILURE_BACKOFF` 마다 다시 시도한다. 그 뒤에는 기존 계기(그 대화의 turn 종료, 위임 종료, 기동)에 맡긴다.
- 위임 자식의 거절은 새 실패 코드를 만들지 않고 `BUSY` 다. 모델에 가는 계약(도구 결과 코드 여섯)을 늘리지 않는다. 문구만 「직접 하거나 앞의 작업이 끝난 뒤 다시 맡기라」 로 바꾼다.
- 흐름 단계가 거절되면 사용자에게 `ORCHESTRATION_STEP_FAILED` 가 아니라 `USER_BUSY` 를 보인다. 사용자가 할 일(진행 중인 작업이 끝난 뒤 다시 보내기)이 다르다.
- 추천 질문이 `USER_BUSY` 로 건너뛰면 실패 시각을 적지 않는다. 적으면 한도가 풀린 뒤에도 재시도 시간 동안 만들지 않는다.

## 작업 항목

### 1. `chat/application/NextTurnDispatcher.java`

`tryPending` 에서 `turns.open` 의 `ApiException` 이 `USER_BUSY` 면 대기 행 번호 목록으로 `holdUnsent(conversation, ids)` 를 부르고 `hub.publish(conversationId, ChatEvent.error(ErrorCode.USER_BUSY.name(), ex.getMessage()))` 를 낸 뒤 참을 돌려준다(이 대화의 다음 turn 은 대기 메시지가 차지했다). `ids` 계산을 `open` 앞으로 옮긴다. `info` 로그에 대화 번호만 남긴다.

새 Spring 사건 `chat/application/WakeRetryDue.java`(`record WakeRetryDue(Long conversationId)`)를 받는 `@EventListener` 메서드 `onWakeRetryDue` 를 더하고 `tryNext(conversationId)` 를 부른다. `onDelegationFinished` 와 같은 모양으로 쓴다.

### 2. `chat/application/DelegationWakeService.java`

- `tryWake` 에서 `turns.open` 의 `ApiException` 이 `USER_BUSY` 면: `lastFailures.put(conversationId, Instant.now(clock))` 를 적고, 이 대화의 연속 거절 수(`ConcurrentHashMap<Long, Integer> busyRetries`)를 하나 늘린다. 그 수가 `MAX_BUSY_RETRIES`(10, 상수)를 넘지 않으면 `FAILURE_BACKOFF` 에 1초를 더한 뒤 `WakeRetryDue(conversationId)` 사건을 내도록 예약한다. 넘으면 예약하지 않고 `info` 로그를 남긴다. 결과의 `result_delivered_at` 은 적지 않는다.
- `turns.open` 이 성공하면 그 대화의 `busyRetries` 를 지운다.
- 예약에는 Spring Boot 가 `@EnableScheduling`(`shared/config/SchedulingConfig.java`)으로 만든 `TaskScheduler` 빈을 주입해 `schedule(Runnable, Instant)` 를 쓴다. 사건은 `ApplicationEventPublisher` 로 낸다.
- 클래스 Javadoc 에 「사용자 실행 한도에 닿으면 상한이 있는 재시도」 를 더한다.

### 3. `orchestration/application/AgentDelegationService.java`, `DelegationResult.java`, `mcp/application/McpToolService.java`

- `delegate` 의 `if (!handoff.rowCreated())` 안에서 `handoff.failure()` 가 `ApiException` 이고 코드가 `USER_BUSY` 면 `rejected(Failure.BUSY, origin, "사용자 동시 실행 한도에 닿았다")` 를 돌려준다. `DataIntegrityViolationException` 검사 앞에 둔다.
- `run` 의 `catch (RuntimeException ex)` 에서 `USER_BUSY` 는 `info` 로 남긴다. 나머지는 그대로 `warn`.
- `DelegationResult.Failure.BUSY` 의 주석을 「서버 전체의 동시 위임이나 그 사용자의 동시 실행이 한도에 닿았다」 로 바꾼다.
- `McpToolService` 의 `BUSY` 문구를 `"지금은 맡길 수 없습니다. 직접 처리하거나 앞의 작업이 끝난 뒤 다시 맡겨 주세요."` 로 바꾼다. 계획을 쓸 때 이 문구를 단언하는 검사는 없었다.

### 4. `orchestration/application/ResearchAndBuildFlow.java`

- `runChild` 에서 `ApiException` 이 `USER_BUSY` 면 `ChildResult.failed(null, ErrorCode.USER_BUSY.name())` 를 돌려준다. 다른 예외는 지금처럼 올린다.
- `stop(root, errorCode)` 는 `errorCode` 가 `USER_BUSY` 면 루트 줄을 `USER_BUSY` 로 실패로 적고 `new ApiException(ErrorCode.USER_BUSY, "a step of the flow hit the user execution limit")` 를 돌려준다.

### 5. `chat/application/StarterSuggestionService.java`, `memory/application/MemoryProposer.java`

- `generate` 의 catch 에서 예외가 `ApiException` 이고 코드가 `USER_BUSY` 이며 실행 줄이 없으면(`execution == null`): `lastFailures` 를 적지 않고 `info` 로그(사용자 번호와 에이전트 번호)만 남긴다. `finally` 의 정리는 그대로 돈다.
- `proposeFrom` 의 catch 에서 같은 조건이면 `info` 로그만 남기고 끝낸다.

### 6. 테스트

모두 `@SpringBootTest` 에 `assistant.user-execution.max-running` 을 작은 값으로 준다. 실행을 붙잡아 두는 데는 `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` 의 `holdSubmits()`/`releaseSubmits()` 나 `beforeAwait(Runnable)` 을 쓴다. 기존 본보기는 `ChatRunningTurnTest`, `PendingBeforeDelegationTest`, `DelegationWakeServiceTest`, `AgentDelegationServiceTest` 다.

- `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` 신규(max-running=2):
  - 같은 사용자가 대화 둘에서 turn 을 붙잡아 둔 채 셋째 대화로 보내면 409 `USER_BUSY` 다. 셋째 대화에 사용자 메시지가 저장되지 않았고 실행 줄도 없다. Hermes 제출 수가 늘지 않았다.
  - 그때 다른 사용자의 보내기는 성공한다.
  - 붙잡은 turn 하나를 끝내면 같은 사용자가 다시 보낼 수 있다(자리가 샜거나 두 번 돌아오지 않는다: `UserExecutionLimiter.used` 가 끝난 뒤 0).
  - 다시 생성도 한도에 닿으면 `USER_BUSY` 이고 이전 답을 바꾸지 않는다.
  - 대기 메시지 turn: 한도에 닿은 상태에서 다른 대화의 turn 이 닫혀 `tryNext` 가 그 대화의 대기 행을 보내려 하면, 대기 행이 남고 `held` 가 참이며 대화 단위 SSE(또는 `ConversationEventHub` 대역)에 `USER_BUSY` 오류가 간다.
- `backend/src/test/java/com/bifos/assistant/orchestration/UserExecutionLimitDelegationTest.java` 신규(max-running=2):
  - 부모 turn 이 돌고 위임 자식 하나가 돈다(2자리). 둘째 `agent_delegate` 는 `BUSY` 를 곧바로 돌려준다(제출 대기 시간 `submit-timeout` 보다 훨씬 짧게, 예를 들어 2초 안에). 실행 줄이 생기지 않았다. 부모 turn 은 그대로 끝까지 돈다. 교착이 없다는 단언이다.
  - 흐름 turn 에서 Researcher 와 Engineer 가 함께 뜨는데 자리가 하나만 남으면, 그 turn 은 `USER_BUSY` 로 끝나고 루트 줄의 `error_code` 가 `USER_BUSY` 다. max-running 을 2 로 두고 다른 대화의 turn 하나를 붙잡아 재현한다.
- `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` 에 더한다: 한도에 닿아 `tryWake` 가 거절되면 결과가 전해지지 않은 채 남고 재시도가 예약된다. 거절이 10번 이어지면 더 예약하지 않는다(`TaskScheduler` 대역으로 예약 수를 센다). 자리가 나면 재시도가 결과를 전한다.
- `backend/src/test/java/com/bifos/assistant/chat/StarterSuggestionServiceTest.java` 와 `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` 에 더한다. 두 파일이 제한기를 실물로 쓰지 않으면 `UserExecutionLimiter` 대역이 `USER_BUSY` 를 던지게 한다: max-running=2, background-reserve=1 에서 turn 자리 하나를 쥔 사용자의 추천 질문과 Memory 제안은 실행 줄을 만들지 않고 Hermes 에 제출하지 않는다. 추천 질문은 실패 시각을 적지 않아 자리가 빈 뒤 다음 읽기에서 만든다.
- 기존 위임, 대화 잠금, 중지, 기동 정리, 깨우기 검사를 지우거나 약하게 바꾸지 않는다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*UserExecutionLimitChatTest' --tests '*UserExecutionLimitDelegationTest' --tests '*DelegationWakeServiceTest' --tests '*StarterSuggestion*' --tests '*MemoryProposer*' --tests '*AgentDelegationServiceTest' --tests '*McpAgentToolsTest' --tests '*PendingBeforeDelegationTest'
./gradlew test
./gradlew qualityCheck
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/quality.sh check
```

모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/WakeRetryDue.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterSuggestionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/UserExecutionLimitDelegationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/StarterSuggestionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | 수정 |
