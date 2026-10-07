# Phase 01. 요청 밖 작업을 BackgroundTasks 로 띄운다

**Execution profile**: deep

## 목표

운영 코드가 직접 띄우던 가상 스레드를 `BackgroundTasks` 빈 하나로 띄운다.
검사가 그 빈을 바꿔 끼워, 검사가 띄운 작업을 끝에 모두 join 할 수 있게 하는 바탕이다.
운영 동작과 스레드 이름은 바뀌지 않는다.

**범위 외**: 검사 쪽 추적 구현과 공통 기반 주석(phase 02), 검사 이전(phase 02, 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-096-운영의-백그라운드-작업은-한-빈으로-띄우고-검사는-끝날-때-모두-join-한다.md`,
`docs/backend/packages.md` 의 `shared/concurrent` 행.

바꿀 자리는 아래와 같다. 스레드 이름 접두사는 지금 값을 그대로 쓴다.

| 파일 | 지금 | 스레드 이름 |
| --- | --- | --- |
| `chat/application/NextTurnDispatcher.java` | `Thread.ofVirtual().name(...).start(...)` | `pending-turn-<conversationId>` |
| `chat/application/TurnCancellation.java` | `Thread.startVirtualThread(() -> closeStream(handle))` 두 곳 | 이름 없음 → `turn-stream-close-<conversationId>` 로 붙인다 |
| `chat/application/DelegationWakeService.java` | 두 곳 | `delegation-wake-<id>`, `delegation-wake-retry-<id>` |
| `chat/application/RestartReconciler.java` | 한 곳 | `restart-reconcile-<executionId>` |
| `chat/application/ChatService.java` | `Thread.startVirtualThread` 사건 스트림 읽기 | 이름 없음 → `turn-event-stream-<runId>` 로 붙인다 |
| `chat/application/StarterSuggestionService.java` | 기본 실행기 `Executors.newVirtualThreadPerTaskExecutor()` | `starter-suggestion` |
| `usage/application/SubagentUsageReconciler.java` | `Thread.startVirtualThread` | 이름 없음 → `subagent-usage-<작업 키>` |
| `usage/application/UserExecutionLimiter.java` | 한 곳 | `remote-end-<executionId>` |
| `task/application/TaskRunStarter.java` | 한 곳 | `task-run-<runId>` |
| `orchestration/application/AgentDelegationService.java` | 한 곳 | `agent-delegate-<rootId>` |
| `proactive/application/ProactiveCheckService.java` | 한 곳 | `proactive-check-<conversationId>` |
| `proactive/application/ProactiveCheckRun.java` | 시간 상한 `unstarted` 한 곳, 멈추기 `start` 한 곳 | `proactive-check-time-limit-<id>`, `proactive-check-stop-<id>` |

경로는 모두 `backend/src/main/java/com/bifos/assistant/` 아래다.
`chat/presentation/ChatEventStreams.java` 와 `notification/presentation/NotificationEventStreams.java` 의 SSE 송신과 heartbeat 스레드는 HTTP 연결에 묶여 있어 바꾸지 않는다.
`orchestration/application/ResearchAndBuildFlow.java` 의 `try (ExecutorService workers = ...)` 는 블록이 스스로 기다리므로 바꾸지 않는다.
`TurnCancellation` 의 `ScheduledExecutorService` 는 유예 시간 타이머이고, 그 콜백 안에서 띄우는 `closeStream` 스레드만 바꾼다.

## 의도 메모

- `@Async` 와 `TaskExecutor` 를 쓰지 않는다. 같은 클래스 안 호출이 프록시를 거치지 않고, 작업마다 스레드 이름을 붙이는 지금 모양과 맞지 않는다.
- 시간 상한 스레드는 만든 뒤 잠금 안에서 저장하고 시작한다. 그래서 `unstarted` 를 둔다. 검사 구현은 시작하지 않은 스레드도 join 대상에 넣어도 된다. 시작하지 않은 스레드의 `join` 은 바로 돌아온다.
- 생성자 주입은 그 클래스의 기존 방식(Lombok `@RequiredArgsConstructor` 나 명시 생성자)을 따른다. 검사가 직접 부르는 명시 생성자가 있으면 인자를 더하고 그 검사를 고친다.

## 작업 항목

### 1. `shared/concurrent/BackgroundTasks.java` 와 `VirtualThreadBackgroundTasks.java`

```java
public interface BackgroundTasks {
    /** 이름 붙인 가상 스레드에서 작업을 바로 시작한다. */
    Thread start(String name, Runnable task);

    /** 이름 붙인 가상 스레드를 만들기만 한다. 부르는 쪽이 시작한다. */
    Thread unstarted(String name, Runnable task);
}
```

`VirtualThreadBackgroundTasks` 는 `@Component` 이고 `Thread.ofVirtual().name(name).start(task)`, `.unstarted(task)` 를 그대로 부른다.
Javadoc 에 ADR-096 을 적는다.

### 2. 위 표의 자리를 `BackgroundTasks` 로 바꾼다

각 클래스가 `BackgroundTasks` 를 주입받는다. `ProactiveCheckRun` 은 빈이 아니므로 `ProactiveCheckRun.Deps` record 에 `BackgroundTasks backgroundTasks` 를 더하고 `ProactiveCheckService.deps()` 가 넣는다.
`StarterSuggestionService` 의 기본 생성자는 `Executor` 자리에 `task -> backgroundTasks.start("starter-suggestion", task)` 를 넘긴다.
`try/catch (RuntimeException | Error ex)` 로 감싼 시작 실패 처리는 그대로 둔다.

### 3. 구조 규칙 `ArchitectureRules`

`backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 규칙을 더한다.
`java.lang.Thread` 의 `ofVirtual()`, `startVirtualThread(Runnable)` 를 부르는 클래스는 `VirtualThreadBackgroundTasks`, `ChatEventStreams`, `NotificationEventStreams` 뿐이다.
`Executors.newVirtualThreadPerTaskExecutor()` 는 `ResearchAndBuildFlow` 만 부른다.
규칙 이름과 Javadoc 은 같은 파일의 기존 규칙 모양을 따른다. 기준 파일을 쓰지 않는다(지금 위반이 0 이 되도록 위 2 를 끝낸 뒤 켠다).
`ArchitectureRulesTest` 가 이 규칙을 돌리게 한다.

### 4. 생성자를 직접 부르는 검사

아래 검사의 `new ...(...)` 에 `new VirtualThreadBackgroundTasks()` 를 넘긴다. 검사가 확인하는 것은 바꾸지 않는다.

- `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java`
- `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java`
- `backend/src/test/java/com/bifos/assistant/chat/StarterSuggestionServiceTest.java`
- `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitBackgroundTest.java`
- `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageReconcilerTest.java`
- `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java`

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew archTest --rerun)
(cd backend && ./gradlew test --tests '*TurnCancellation*' --tests '*StarterSuggestionServiceTest' --tests '*UserExecutionLimitBackgroundTest' --tests '*SubagentUsageReconcilerTest' --tests '*AgentDelegationServiceRaceTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
git grep -ln "Thread.ofVirtual\|startVirtualThread" -- backend/src/main
```

- 모두 종료 코드 0
- 마지막 줄의 결과가 `VirtualThreadBackgroundTasks.java`, `ChatEventStreams.java`, `NotificationEventStreams.java` 셋뿐이다
- `archTest` 의 새 규칙이 기준 파일 없이 통과한다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/shared/concurrent/BackgroundTasks.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/concurrent/VirtualThreadBackgroundTasks.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterSuggestionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/SubagentUsageReconciler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionLimiter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/StarterSuggestionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitBackgroundTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageReconcilerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java` | 수정 |
