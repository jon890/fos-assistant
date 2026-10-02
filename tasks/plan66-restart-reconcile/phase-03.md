# Phase 03. 기동 때 잡고 묻는 `RestartReconciler`

**Execution profile**: deep

## 목표

Control Plane 이 뜰 때 `RUNNING` 으로 남은 실행을 Hermes 에 물어 정하는 `RestartReconciler` 를 만들고, 모두 실패로 바꾸던 `OrphanedExecutionSweeper` 를 지운다.
아직 도는 대화 turn 과 위임 실행에는 다시 붙고, 정하는 동안 그 대화에 새 turn 이 열리지 않게 한다.

**범위 외**: e2e 와 가짜 Hermes, 화면 문구(phase 04).

## 컨텍스트

- phase 01 이 `HermesRunsClient.lookupRun(String apiBaseUrl, String profileName, String runId)` 와 `HermesRunLookup`(`State.RUNNING`, `FINISHED`, `NOT_FOUND`)을 만들었다. 닿지 못하면 `ApiException` 이다
- phase 02 가 `chat.application.RecoveredRunRecorder` 를 만들었다. `settle(Long executionId, HermesRunResult result)`, `failWithout(Long executionId, String errorCode)`, `kindOf(AgentExecution row)` 이고, 이미 끝난 줄이면 거짓을 돌려준다. `RecoveredRunKind` 는 `CHAT_TURN`, `DELEGATION`, `FLOW`, `AUXILIARY` 다
- 지울 것: `backend/src/main/java/com/bifos/assistant/usage/application/OrphanedExecutionSweeper.java` 와 `backend/src/test/java/com/bifos/assistant/usage/OrphanedExecutionSweeperTest.java`
- turn 잠금은 `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` 다
  - `open(Long userId, Long conversationId)`: 잠금을 잡고 `TurnHandle` 을 준다. 이미 잡혀 있으면 `ApiException(CONVERSATION_BUSY)`
  - `rekey(TurnHandle, Long executionId)`: 표시에 실행 번호를 붙인다. `running` 경로와 중지 경로가 이 번호로 찾는다
  - `trackRun(Long executionId, String apiBaseUrl, String profileName, String runId)`: 중지할 run 을 표시에 붙인다
  - `isStopConfirmed(TurnHandle)`, `markStopped(TurnHandle)`, `markFinished(TurnHandle)`, `close(TurnHandle)`. `close` 가 닫기 리스너(`NextTurnDispatcher.onTurnClosed`)를 부른다
- 사용자의 중지는 `ChatService.stop(CurrentUser, Long executionId)` 다. `turns.find(executionId)` 로 표시를 찾아 붙은 run 에 중지를 보내고 `confirmStop` 한다. **고치지 않아도 다시 붙은 turn 에 듣는다.** 표시와 run 만 붙어 있으면 된다
- 중지로 끝난 turn 은 잠금을 풀기 전에 대기 줄을 멈춘다. `ChatService.markStoppedAndHoldPending` 이 선례다. `turns.markStopped(handle)` 뒤 `transactions.executeWithoutResult(status -> pendingMessages.markHeld(conversationId, true))` 이고, 멈추다 실패하면 경고 로그만 남긴다
- 기동 뒤 깨우기는 `NextTurnDispatcher.dispatchAfterStartup()`(`@EventListener(ApplicationReadyEvent.class)`, `@Order(10)`)이다. 잠금이 잡힌 대화는 `tryPending` 이 `CONVERSATION_BUSY` 를 받아 건너뛰고, 잠금이 풀릴 때 닫기 리스너가 다시 부른다
- `agent_stop` 은 `AgentDelegationService.stop` 이다. 이 프로세스에 중지 표시가 없는 도는 실행은 `stopDetached` 로 Hermes 에 중지만 보낸다. 다시 붙은 위임 실행이 이 경로를 탄다. 동작은 그대로 두고 Javadoc 의 「기동 정리가 그 줄을 끝내므로」 만 지금 동작에 맞게 고친다
- 설정 클래스는 `@ConfigurationProperties` record 로 두면 `AssistantApplication` 의 `@ConfigurationPropertiesScan` 이 읽는다. 선례는 `chat.application.DelegationWakeProperties` 다. `hermes.poll-interval` 과 `hermes.run-timeout` 은 `HermesProperties.pollInterval()`, `runTimeout()` 이다
- 웹 서버를 여는 lifecycle 의 phase 는 `org.springframework.boot.web.server.context.WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE` 다(Spring Boot 4.1). `SmartLifecycle` 은 phase 가 작은 것부터 시작한다
- 테스트 profile(`backend/src/test/resources/application-test.yml`)은 `hermes.poll-interval: 10ms`, `hermes.run-timeout: 1s` 다
- 가상 스레드를 띄우는 선례는 `NextTurnDispatcher.tryPending` 의 `Thread.ofVirtual().name(...).start(...)` 다

**근거 문서**: `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」 전체, `docs/adr/ADR-059-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`

## 의도 메모

- **잡기와 묻기를 나눈다.** 잡기는 웹 서버보다 먼저 끝나야 하고 Hermes 를 부르지 않는다. 묻기는 `ApplicationReadyEvent` 뒤에 한다. 묻기가 `DelegationFinished` 를 내거나 잠금을 풀면 `NextTurnDispatcher` 가 turn 을 여는데, 그것은 애플리케이션이 다 뜬 뒤여야 한다
- **다음 turn 을 여는 자리는 `NextTurnDispatcher.tryNext` 하나다.** `RestartReconciler` 는 turn 을 열지 않고 `tryNext` 도 직접 부르지 않는다. 잠금을 풀면 닫기 리스너가 부른다
- 위임 실행과 그 밖의 실행은 잠금을 잡지 않는다. 자기 Hermes session 으로 돌아 대화의 session 과 겹치지 않는다. 위임 자식이 도는 동안 부모 대화는 보통 때도 열려 있다
- 내려갈 때 묻던 스레드가 줄을 실패로 적으면 안 된다. 다음 기동이 다시 정해야 한다
- 상한을 넘겼을 때 중지를 보낸 뒤 Hermes 의 취소 결과를 기다리지 않는다. 닿지 않아 넘긴 경우가 있어 기다림에 끝이 없다
- 테스트 profile 은 기동 때 자동으로 돌지 않게 끈다. 다른 테스트가 남긴 `RUNNING` 줄에 백그라운드 스레드가 붙으면 그 테스트의 단언이 흔들린다. 검사는 `claim()` 과 `reconcile()` 을 직접 부른다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconcileProperties.java` (신규)

```java
@ConfigurationProperties(prefix = "assistant.restart-reconcile")
public record RestartReconcileProperties(@DefaultValue("true") boolean enabled, Duration maxWait) {}
```

`maxWait` 이 null 이면 쓰는 쪽이 `HermesProperties.runTimeout()` 을 쓴다. 0 이하이면 기동을 멈춘다(`IllegalStateException`, 설정 이름을 글에 넣는다).

`backend/src/main/resources/application.yml` 의 `assistant:` 아래에 더한다.

```yaml
  restart-reconcile:
    # 기동할 때 RUNNING 으로 남은 실행을 Hermes 에 물어 정한다(ADR-059). 끄면 그 줄이 그대로 남는다
    enabled: true
    # 다시 붙어 기다리는 상한. 비우면 hermes.run-timeout 과 같다. 넘으면 중지를 보내고 FAILED 로 적는다
    max-wait: ${ASSISTANT_RESTART_RECONCILE_MAX_WAIT:}
```

빈 문자열이 `Duration` 으로 묶일 때 null 이 되는지 확인한다. 되지 않으면 yml 에서 그 줄을 주석으로만 두고 기본값을 코드에 맡긴다.

`backend/src/test/resources/application-test.yml` 의 `assistant:` 아래에 `restart-reconcile: { enabled: false }` 를 더한다(그 파일의 들여쓰기 형식을 따른다).

### 2. `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java` (신규)

`@Component`, `SmartLifecycle` 을 구현한다.

- `getPhase()`: `WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1`
- `start()`: `properties.enabled()` 면 `claim()`. 예외가 나도 기동을 막지 않고 오류 로그를 남긴다. 그 뒤 `isRunning()` 이 참이다
- `stop()`: 내려가는 중이라고 표시한다. 묻던 스레드가 다음 바퀴에서 줄을 적지 않고 끝난다. 자고 있는 스레드는 깨운다(interrupt)
- `@EventListener(ApplicationReadyEvent.class) @Order(0) void onReady()`: `properties.enabled()` 면 `reconcile()`

공개 메서드 둘은 테스트가 직접 부른다.

**`public void claim()`**

1. `executions.findByStatus(ExecutionStatus.RUNNING)` 을 읽어 기억한다(묻기가 이 목록을 쓴다)
2. `hermesRunId()` 가 있고 `parentExecutionId() == null` 이고 `conversationId() != null` 인 줄마다(`CHAT_TURN` 과 `FLOW` 의 뿌리다)
   - `turns.open(row.userId(), row.conversationId())` 로 잠금을 잡고 `turns.rekey(handle, row.id())`
   - 에이전트(`agents.findById(row.agentId())`)가 있으면 `turns.trackRun(row.id(), agent.apiBaseUrl(), row.profileName(), row.hermesRunId())`
   - 같은 대화에 그런 줄이 둘이면 `open` 이 `CONVERSATION_BUSY` 를 던진다. 먼저 잡은 잠금을 함께 쓰고, 그 대화의 줄이 모두 정해진 뒤에 푼다
3. Hermes 를 부르지 않고 실행 줄을 고치지 않는다

**`public void reconcile()`**

1. 기억한 줄 가운데 `hermesRunId()` 가 없는 것: `recorder.failWithout(row.id(), "ORPHANED")`
2. `hermesRunId()` 는 있는데 에이전트 행이 없는 것: `recorder.failWithout(row.id(), "ORPHANED")` 뒤 잡은 잠금이 있으면 푼다
3. 나머지는 줄마다 가상 스레드(`restart-reconcile-<executionId>`)를 띄워 아래를 돈다. 스레드를 띄우지 못하면 잡은 잠금을 푼다
4. `claim()` 없이 불리면 스스로 `claim()` 부터 한다. 두 번 불려도 같은 줄에 스레드를 둘 띄우지 않는다

**줄 하나를 정하는 바퀴**

```
상한 = 지금 + (properties.maxWait() 또는 hermes.runTimeout())
닿았다 = 거짓, 중지보냄 = 거짓, 간격 = hermes.pollInterval()
되풀이:
  내려가는 중이면 끝낸다 (줄을 적지 않는다. 잠금도 풀지 않는다)
  lookupRun(agent.apiBaseUrl(), row.profileName(), row.hermesRunId())
    FINISHED  -> recorder.settle(row.id(), result) 뒤 끝
    NOT_FOUND -> recorder.failWithout(row.id(), "REMOTE_RUN_LOST") 뒤 끝
    RUNNING   -> 닿았다 = 참, 간격 = pollInterval
                 종류가 FLOW 이고 아직 중지를 보내지 않았으면 hermes.stop(...) 을 보낸다(실패해도 경고 로그만, 다음 바퀴에 다시 보낸다)
    ApiException -> 간격 = min(간격 * 2, 5초)
  지금이 상한을 넘었으면:
    hermes.stop(...) 을 한 번 보낸다(실패해도 경고 로그만)
    recorder.failWithout(row.id(), 닿았다 ? "RECONCILE_TIMEOUT" : "RECONCILE_UNREACHABLE") 뒤 끝
  간격만큼 잔다
끝나면: 잡은 잠금을 푼다
```

- `ApiException` 이 아닌 예상하지 못한 예외는 오류 로그를 남기고 `recorder.failWithout(row.id(), "ORPHANED")` 로 적은 뒤 잠금을 푼다. 줄이 `RUNNING` 으로, 대화가 잠긴 채로 남지 않게 한다
- 오류 코드 넷(`ORPHANED`, `REMOTE_RUN_LOST`, `RECONCILE_TIMEOUT`, `RECONCILE_UNREACHABLE`)은 이 클래스의 상수로 둔다. `ErrorCode` enum 에 더하지 않는다. HTTP 응답으로 나가지 않는 값이고 `ORPHANED` 도 그렇게 있었다

**잠금을 푼다**

그 대화의 남은 줄 수가 0 이 되면 한 번만 한다.

1. `turns.isStopConfirmed(handle)` 이고 그 실행 줄이 `CANCELLED` 로 끝났으면 `turns.markStopped(handle)` 뒤 그 대화의 대기 줄을 멈춘다(`pendingMessages.markHeld(conversationId, true)`, 트랜잭션, 실패는 경고 로그만)
2. `turns.markFinished(handle)`, `turns.close(handle)`

대기 줄이 멈췄다는 알림은 `NextTurnDispatcher.onTurnClosed` 가 `TurnClosed.stopped()` 를 보고 낸다. 여기서 내지 않는다.

### 3. 지우기와 주석

- `OrphanedExecutionSweeper.java` 와 `OrphanedExecutionSweeperTest.java` 를 지운다
- `NextTurnDispatcher.dispatchAfterStartup` 의 Javadoc 「기동 정리가 끊긴 위임 실행을 FAILED 로 적은 뒤에 돈다」 를 지금 동작으로 고친다. `RestartReconciler` 가 잠금을 잡은 대화는 건너뛰고 그 잠금이 풀릴 때 다시 온다는 것, run 번호가 없어 실패로 적힌 위임 결과는 이 깨우기가 전한다는 것
- `AgentDelegationService.stop` 의 Javadoc 마지막 문단을 고친다. 이 프로세스에 중지 표시가 없는 도는 실행은 기동 정리가 다시 붙어 있는 실행이고, 중지를 보내면 그 실행이 `CANCELLED` 로 적힌다는 것
- `git grep -n "OrphanedExecutionSweeper\|기동 정리" backend/src` 로 남은 언급을 찾아 지금 동작과 다른 주석을 고친다. 고친 파일은 「변경 파일」 에 더한다

### 4. `backend/src/test/java/com/bifos/assistant/chat/RestartReconcilerTest.java` (신규)

`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(ChatServiceTest.StubRuntime.class)`. Hermes 는 `StubHermesRunsClient` 의 `willLookup`, `willFailLookup`, `lookups()`, `stopped()` 로 다룬다. 실행 줄은 저장소로 직접 `RUNNING` 으로 만든다. 묻기는 가상 스레드에서 돌므로 결과는 기다려 읽는다(Awaitility 가 이미 의존에 있으면 그것을, 없으면 `ChatStopTest` 가 쓰는 기다림 방식을 쓴다). `@AfterEach` 에서 대역을 `reset()` 하고 남은 잠금이 없는지 본다.

상한을 줄여야 하는 경우는 그 테스트 메서드가 든 클래스에 `@TestPropertySource(properties = "assistant.restart-reconcile.max-wait=300ms")` 를 주거나, 상한을 인자로 받는 package-private `reconcile(Duration maxWait)` 을 두어 부른다.

이슈 #98 의 완료 조건이 요구하는 여섯 경우를 모두 둔다.

| 경우 | 준비 | 기대 |
| --- | --- | --- |
| 원격 성공 | 대화 turn 줄, `willLookup(run, finished(completed, 답, usage))` | 줄 `SUCCEEDED`, 토큰과 비용, `ASSISTANT` 메시지 하나, 잠금이 풀렸다(`turns.markOf(conversationId).running()` 이 거짓) |
| 원격 실행 중 | `willLookup(run, running(), running(), finished(completed...))` | 처음에는 줄이 `RUNNING` 이고 `turns.markOf(conversationId)` 가 `running=true`, `executionId=그 줄` 이다. 그 사이 `chat.send(...)` 는 `CONVERSATION_BUSY`. 끝나면 `SUCCEEDED` 와 메시지, 잠금 해제. `stub.stopped()` 가 비어 있다 |
| 원격 실패 | `finished(failed, usage)` | `FAILED`, `errorCode` 가 `FAILED`, 토큰이 남았다, 메시지 없음 |
| 404 | `willLookup` 을 주지 않는다 | `FAILED`, `REMOTE_RUN_LOST`. `lookups()` 에 그 run 이 한 번 |
| 일시적 연결 실패 | `willFailLookup(run, new ApiException(HERMES_UNAVAILABLE, ...), 3)` 뒤 `finished(completed...)` | 실패하는 동안 줄이 `RUNNING` 이고 잠금이 잡혀 있다. 그 뒤 `SUCCEEDED` |
| run 번호 미저장 | `hermesRunId` 없는 `RUNNING` 줄 | `FAILED`, `ORPHANED`. `lookups()` 가 비어 있다 |

여기에 더한다.

| 경우 | 기대 |
| --- | --- |
| 상한까지 계속 돈다 | `stub.stopped()` 에 그 run, 줄 `FAILED` `RECONCILE_TIMEOUT`, 잠금 해제 |
| 상한까지 닿지 못한다 | 줄 `FAILED` `RECONCILE_UNREACHABLE`, 잠금 해제 |
| 위임 실행이 아직 돌다 성공 | 줄 `SUCCEEDED`, `outputText`. 부모 대화의 잠금을 잡지 않았다. `DelegationWakeServiceTest` 의 방식으로 부모 대화에 결과가 한 번 전해졌는지 본다(`resultDeliveredAt` 이 찼고 알림 줄이 하나) |
| 같은 줄에 `claim()` 과 `reconcile()` 을 두 번 | 메시지 하나, `resultDeliveredAt` 한 번, 토큰과 `finishedAt` 이 첫 기록 그대로 |
| 흐름 에이전트의 뿌리 줄이 아직 돈다 | `stub.stopped()` 에 그 run. `onStop` 으로 다음 조회를 `finished(cancelled)` 로 바꾸면 줄이 `CANCELLED`. 메시지 없음 |
| 다시 붙은 대화 turn 을 `chat.stop(user, executionId)` 으로 멈춘다 | `stub.stopped()` 에 그 run. 조회가 `finished(cancelled, 일부 답)` 을 주면 줄 `CANCELLED`, 그 대화의 대기 행이 `held` |
| 정하는 동안 쌓인 대기 메시지 | 잠금이 잡힌 동안 `PendingMessageService` 로 더한 글이 남아 있다가, 잠금이 풀린 뒤 `USER` 메시지로 저장되고 새 실행이 제출된다(`stub.received()`) |
| `stop()` 뒤 | 묻던 줄이 `RUNNING` 으로 남는다 |
| `getPhase()` | `WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE` 보다 작다 |

`backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` 와 `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` 에 「기동 정리가 끝낸다」 를 전제로 한 단언이 있으면 지금 동작에 맞게 고치고 「변경 파일」 에 더한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.RestartReconcilerTest' --tests 'com.bifos.assistant.chat.*' --tests 'com.bifos.assistant.orchestration.*' --tests 'com.bifos.assistant.usage.*' --tests 'com.bifos.assistant.architecture.*'
! git grep -n "OrphanedExecutionSweeper" -- backend/src/main docs
```

첫 명령은 종료 코드 0, 둘째는 일치하는 줄이 없어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconcileProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/RestartReconcilerTest.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/OrphanedExecutionSweeper.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/usage/OrphanedExecutionSweeperTest.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
