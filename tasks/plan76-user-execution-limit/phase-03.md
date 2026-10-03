# Phase 03. Hermes 에서 끝났는지 모르는 run 의 자리

**Execution profile**: standard

## 목표

실행 줄을 먼저 끝냈는데 Hermes 에서 그 run 이 끝났는지 모르는 경우, 그 사용자의 자리를 메모리에 쥐고 Hermes 가 끝났다거나 그 run 을 모른다고 답할 때 돌려준다.
원격 종료가 확인되지 않은 run 을 실제로 끝난 것으로 보지 않기 위해서다.

**범위 외**: 실행 줄의 상태 계약은 바꾸지 않는다. 줄은 지금처럼 `FAILED` 로 적는다. 제출 응답을 받지 못해 run 번호가 없는 실행은 물을 수 없어 다루지 않는다.

## 컨텍스트

**근거 문서**: `docs/backend/execution-limit.md` 의 「원격 종료 확인」 절, `docs/adr/ADR-069-사용자-전체-실행-한도는-turn-자리와-실행-줄을-사용자-잠금-하나에서-센다.md`, `docs/hermes/concurrency.md` 의 「pool 말고 걸리는 것」 절 아래 사용자 실행 한도 단락.

phase 01 이 만든 `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionLimiter.java` 는 사용자별 원격 종료 확인 자리(`Map<Long, Set<Long>>`, 실행 번호 모음)를 이미 합계에 더한다. 이 phase 는 그 모음을 채우고 비우는 메서드를 더한다.

Hermes 쪽 계약:
- `hermes/HermesRunsClient.java`: `stop(String apiBaseUrl, String profileName, String runId)`, `lookupRun(String apiBaseUrl, String profileName, String runId)` 는 `hermes/dto/HermesRunLookup` 을 돌려준다. `state()` 는 `RUNNING`, `FINISHED`, `NOT_FOUND` 이고, 닿지 못하면 `ApiException` 을 던진다.
- `hermes/HermesProperties.java` 의 `pollInterval()`(기본 700ms), `runTimeout()`(기본 5m).
- 기동 정리의 묻기 반복이 같은 일을 한다. `chat/application/RestartReconciler.java` 의 `settleByAsking` 이 닿지 못하면 간격을 5초까지 늘리는 본보기다.

자리를 쥐어야 하는 곳(계획을 쓸 때 읽은 코드):

| 파일 | 자리 | run 번호와 사용자 |
| --- | --- | --- |
| `chat/application/ChatService.java` | private `awaitCompletion(PendingTurn, String runId)` 의 `catch (ApiException ex)`. `executions.fail` 앞 | `pending.user().id()`, `pending.execution().id()`, `pending.command().apiBaseUrl()`, `pending.command().profileName()` |
| `orchestration/application/AgentRunner.java` | `hermes.awaitCompletion(command, runId)` 의 `catch (RuntimeException ex)` 두 갈래(취소와 실패) 모두. 제출 직후 실패의 `stopSubmitted(agent, execution, runId)` 뒤 | `user.id()`, `execution.id()`, `agent.apiBaseUrl()`, `agent.hermesProfile()` |
| `memory/application/MemoryProposer.java` | `hermes.awaitCompletion` 이 예외로 끝난 경우 | `user.id()`, `proposalExecution.id()`, `agent.apiBaseUrl()`, `agent.hermesProfile()` |
| `chat/application/StarterSuggestionService.java` | `hermes.awaitCompletion` 이 예외로 끝난 경우 | `user.id()`, `execution.id()`, `agent.apiBaseUrl()`, `agent.hermesProfile()` |
| `chat/application/RestartReconciler.java` | `giveUp(Agent, AgentExecution row, boolean reached, boolean finished)` 에서 `finished` 가 거짓일 때 | `row.userId()`, `row.id()`, `agent.apiBaseUrl()`, `row.profileName()`, `row.hermesRunId()` |

`MemoryProposer` 와 `StarterSuggestionService` 는 submit 부터 결과 처리까지 한 try 안이다. run 번호를 try 밖 지역 변수로 두고, `awaitCompletion` 이 돌려준 결과를 받기 전에 예외가 났을 때만 자리를 쥔다.

## 의도 메모

- 줄을 `RUNNING` 으로 남기는 안은 버렸다. 사용자는 이미 시간 초과를 받았는데 사용량 화면이 도는 중으로 보이고, 기동 정리가 그 줄을 대화 turn 으로 다시 잡는다(ADR-069 「대안 기각」).
- 이 자리는 재기동하면 사라진다. 기동 정리는 끝난 줄을 보지 않는다. 문서에 이미 적은 한계다. 메모리 밖에 저장하지 않는다.
- 상한에 닿아 자리를 돌려줄 때는 실행 번호와 run 번호를 담은 `warn` 로그를 남긴다(코디네이터 요구).
- 중지는 run 마다 한 번만 보낸다. `RestartReconciler.giveUp` 과 `AgentRunner.stopSubmitted` 는 이미 보냈으므로 `stopAlreadySent` 를 참으로 넘긴다. 그래야 `RestartReconcilerTest` 의 `assertThat(stub.stopped()).containsExactly(row.hermesRunId())` 같은 기존 단언이 그대로 선다. 보내다 실패해도 묻기는 계속한다.
- 확인 스레드는 검사가 끝난 뒤에도 돌 수 있다. `StubHermesRunsClient.lookupRun` 은 부를 때마다 `lookups` 에 적으므로, 다음 검사의 `lookups()).isEmpty()` 단언에 섞일 수 있다. 자리를 쥐게 만드는 검사는 끝나기 전에 그 run 의 조회가 `NOT_FOUND` 나 `FINISHED` 를 답하게 하고 `used` 가 원래 값으로 돌아올 때까지 기다린다.

## 작업 항목

### 1. `usage/application/UserExecutionLimiter.java`

- 생성자에 `HermesRunsClient`, `HermesProperties` 를 더한다(`usage` 는 `hermes` 위 층이라 쓸 수 있다).
- `public void holdUntilRemoteEnds(Long userId, Long executionId, String apiBaseUrl, String profileName, String runId, boolean stopAlreadySent)`:
  - `runId` 가 null 이거나 비면 아무것도 하지 않는다.
  - 사용자 잠금 안에서 그 사용자의 모음에 `executionId` 를 더한다. 이미 있으면 그대로 돌아간다(같은 실행을 두 번 쥐지 않는다).
  - 가상 스레드 `remote-end-<executionId>` 를 띄운다. 띄우지 못하면 자리를 곧바로 돌려주고 `warn` 을 남긴다.
  - 스레드: `stopAlreadySent` 가 거짓이면 `stop` 을 한 번 보낸다(예외는 `warn`). 그 뒤 `lookupRun` 을 반복한다. `FINISHED` 나 `NOT_FOUND` 면 돌려준다. `RUNNING` 이면 `pollInterval` 뒤 다시, `ApiException` 이면 간격을 두 배씩 5초까지 늘린다.
    상한은 `properties.remoteEndMaxWait()` 이고 null 이면 `hermesProperties.runTimeout()` 이다. 넘으면 `log.warn("Hermes 에서 끝났는지 확인하지 못한 채 사용자 자리를 돌려준다 executionId={} runId={}", ...)` 를 남기고 돌려준다. 어느 경우든 `finally` 에서 돌려준다.
  - 돌려주기는 잠금 안에서 모음에서 빼고 빈 모음은 맵에서 지운다.
- 반복의 시각과 잠자기는 시험에서 줄일 수 있게 `Clock` 이나 패키지 접근 생성자로 바꿀 수 있게 둔다. 대역 Hermes 의 `pollInterval` 을 짧게 주는 방법도 된다.

### 2. 위 표의 다섯 파일

표의 자리에서 `limiter.holdUntilRemoteEnds(...)` 를 부른다. `stopAlreadySent` 는 `RestartReconciler.giveUp`(중지를 보낸 뒤)과 `AgentRunner` 의 `stopSubmitted` 뒤에서만 참이다. 실행 줄을 실패로 적기 전에 부른다. 잠깐 두 번 세는 것은 한도를 덜 주는 쪽이라 괜찮다. 거꾸로 하면 잠깐 덜 센다.
각 자리에 한 줄 주석: 「Hermes 에서 끝났는지 모르는 run 은 끝날 때까지 사용자 자리를 쥔다(ADR-069)」.
`AgentRunner`, `MemoryProposer`, `StarterSuggestionService`, `ChatService`, `RestartReconciler` 에 `UserExecutionLimiter` 를 주입한다. 이 클래스들과 제한기를 직접 만드는 테스트의 생성자 인자를 맞춘다(`grep -rn "new AgentRunner(\|new MemoryProposer(\|new StarterSuggestionService(\|new RestartReconciler(\|new UserExecutionLimiter(" backend/src/test`). phase 01 이 `TurnCancellationTest` 와 `TurnCancellationCloseTest` 에서 만든 제한기가 여기 든다.

### 3. 테스트

- `backend/src/test/java/com/bifos/assistant/usage/UserExecutionLimiterTest.java` 에 더한다. 클래스에 `@Import(ChatServiceTest.StubRuntime.class)` 를 달아 `HermesRunsClient` 를 `StubHermesRunsClient` 로 받고, `willLookup(runId, ...)`, `willFailLookup(runId, ex, times)` 로 답을 정한다. `assistant.user-execution.remote-end-max-wait` 와 `hermes.poll-interval` 은 속성으로 짧게 준다:
  - 쥐면 `used` 가 1 늘고 `stop` 이 한 번 갔다. `stopAlreadySent` 가 참이면 `stop` 이 가지 않는다. `lookupRun` 이 `RUNNING` 두 번 뒤 `FINISHED` 면 돌려준다. 돌려준 뒤 `used` 가 원래 값이다.
  - `NOT_FOUND` 면 돌려준다.
  - 계속 `RUNNING` 이면 상한 뒤 돌려준다. 로그 단언은 하지 않아도 된다.
  - 같은 실행 번호로 두 번 쥐어도 1 만 는다.
  - 쥔 동안 같은 사용자의 `acquireTurn` 이 그만큼 덜 된다(max-running=3 에서 쥔 자리 1, turn 2 뒤 셋째가 `USER_BUSY`).
- `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java`(phase 02 가 만든 파일)에 더한다: `StubHermesRunsClient.willFail(new ApiException(ErrorCode.HERMES_RUN_TIMEOUT, ...))` 처럼 대기에서 시간 초과로 끝나는 turn 뒤에, 실행 줄은 `FAILED`(`HERMES_RUN_TIMEOUT`) 이고 그 사용자의 `used` 는 1 이다. `lookupRun` 이 끝났다고 답하면 0 이 된다. 대역의 실패가 submit 이 아니라 await 에 걸리는지 `StubHermesRunsClient` 를 읽고 맞는 메서드를 고른다.
- `backend/src/test/java/com/bifos/assistant/chat/application/RestartReconcilerTest.java` 에 더한다: 상한을 넘겨 `giveUp` 이 `FAILED` 로 적은 줄의 사용자 자리가 원격 종료 확인 자리로 남고, 중지는 한 번만 갔다. 끝나기 전에 그 run 의 조회를 `NOT_FOUND` 로 바꿔 자리가 돌아오는 것까지 본다.
- 기존 검사의 단언을 약하게 바꾸지 않는다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*UserExecutionLimiterTest' --tests '*UserExecutionLimitChatTest' --tests '*RestartReconcilerTest' --tests '*AgentRunner*' --tests '*MemoryProposerTest' --tests '*StarterSuggestionServiceTest'
./gradlew test
./gradlew qualityCheck
```

```bash
# cwd: 저장소 root
scripts/quality.sh check
```

모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionLimiter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterSuggestionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UserExecutionLimiterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/RestartReconcilerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
