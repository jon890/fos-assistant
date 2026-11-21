# Phase 02. 중립 port 와 Hermes 어댑터를 만든다

**Execution profile**: standard

## 목표

런타임 이름이 없는 실행 port `AgentRuntime` 과 그 타입, 런타임을 고르는 `AgentRuntimes`, Hermes 구현 `HermesAgentRuntime` 을 **새로 더한다. 지금 있는 코드는 부르는 쪽도 Hermes 쪽도 고치지 않는다**(`Agent` 에 메서드 둘을 더하는 것만 예외다). 이 phase 가 끝나면 새 port 는 테스트만 쓴다.

**범위 외**: 부르는 쪽을 새 port 로 바꾸는 일(phase 03).

## 컨텍스트

- 지금 port 는 `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRunsClient.java` 다. 메서드마다 `(String apiBaseUrl, String profileName, …)` 을 받고 `HermesRunCommand`, `HermesRunResult`, `HermesRunLookup`(`hermes/application/dto/`)을 주고받는다. 사건 스트림은 `hermes/infra/HermesRunEventStream.java` 의 `open(apiBaseUrl, profileName, runId, Consumer<HermesRunEvent> onEvent, Consumer<Closeable> onOpened, ToolDetailScope scope)` 다.
- **이 둘과 그 타입은 `hermes` 안의 내부 경계로 남는다**(설계 DECISION6). 통합 테스트가 `StubHermesRunsClient`(`backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java`, `testsupport/IntegrationTestDoubles.java` 가 빈으로 등록)와 `@MockitoBean HermesRunEventStream`(`testsupport/BackendIntegrationTest.java`)으로 대신하기 때문이다.
- `HermesRunResult` 의 판정: `succeeded()` 는 `"completed".equalsIgnoreCase(status)` 하나다. `providerBlocked()` 는 오류 글이 `PROVIDER_AUTH_FAILED_PREFIX` 로 시작하는지 본다.
- 부르는 쪽이 지금 결과의 `status()` 로 하는 일은 둘이다. 취소인지 보기(`ChatTurnRunner`, `AgentRunner`, `RecoveredRunRecorder` 의 비교식을 읽어 어떤 문자열을 취소로 보는지 확인한다)와, 대문자로 바꿔 실행 기록의 오류 코드로 저장하거나 로그에 남기기.
- Hermes 가 보내는 사건 이름: `message.delta`, `message.interim`, `tool.started`, `tool.completed`, `subagent.start`, `subagent.complete`, `run.completed`, `run.failed`, `run.cancelled`, `run.interrupted`(부르는 쪽 `chat/turn/application/ChatRunEvents.java`, `usage/execution/application/ExecutionEventRecorder.java` 에서 확인).
- 에이전트의 주소와 profile 은 `agent/domain/Agent.java` 의 `apiBaseUrl()`, `hermesProfile()` 이다.

**요구사항**: REQ1

**근거 문서**: `docs/features/runtime-abstraction/design.md` 의 「인터페이스와 데이터」, 「대상을 만드는 두 길」, 「검증 전략」

## 의도 메모

- `RuntimeTarget` 의 칸 이름은 `kind`, `location`, `unit` 이다. 중립 타입의 주석은 「구현이 뜻을 정한다」 로 쓰고, Hermes 에서 `location` 이 API server 주소, `unit` 이 profile 이름이라는 설명은 `HermesAgentRuntime` 의 Javadoc 에 쓴다.
- 하지 못하는 동작은 새 enum 값이 아니라 지금 있는 값으로 답한다(설계 DECISION5). `SteerOutcome` 에 값을 더하지 않는다.
- 구현이 에이전트 줄을 직접 읽게 하지 않는다. `hermes` 가 `agent` 를 기대면 층 순서가 뒤집힌다.
- **저장되는 오류 코드 글자가 바뀌면 안 된다.** `RunResult.statusLabel` 은 Hermes 의 상태 문자열을 받은 그대로 넘기는 칸이다.

## 작업 항목

### 1. `runtime.domain`

- `RuntimeKind`: enum, 값은 `HERMES` 하나.
- `RuntimeTarget`: `record RuntimeTarget(RuntimeKind kind, String location, String unit)`. `kind` 와 `unit` 이 null 이면 생성자에서 `IllegalArgumentException`. 런타임 이름이 든 정적 메서드는 두지 않는다.

### 2. `runtime.application.dto`

| 타입 | 칸 |
| --- | --- |
| `RunCommand` | `RuntimeTarget target`, `String input`, `String instructions`, `String sessionId`, `String provider`, `String model`, `String reasoningEffort`, `List<RunImage> images`(null 이면 빈 목록, 방어 복사). 사진 없는 보조 생성자 하나 |
| `RunEventKind` | `MESSAGE_DELTA`, `MESSAGE_INTERIM`, `TOOL_STARTED`, `TOOL_COMPLETED`, `SUBAGENT_STARTED`, `SUBAGENT_COMPLETED`, `RUN_COMPLETED`, `RUN_FAILED`, `RUN_CANCELLED`, `RUN_INTERRUPTED`, `OTHER`. `boolean terminal()` 은 `RUN_` 넷에서 참 |
| `RunEvent` | `HermesRunEvent` 와 칸이 같고 첫 칸만 `String type` 대신 `RunEventKind kind` |
| `RunOutcome` | `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `RunResult` | `runId`, `sessionId`, `RunOutcome outcome`, `String statusLabel`, `output`, `model`, `provider`, `error`, `TokenUsage usage`, `SessionRuntime runtime`, `pendingSteer`, `boolean pendingSteerKnown`, `boolean providerBlocked`. `boolean succeeded()` 는 `outcome == SUCCEEDED`. 보조 생성자를 두지 않는다 |
| `RunLookup` | `HermesRunLookup` 과 같은 모양(`State { RUNNING, FINISHED, NOT_FOUND }`, `RunResult result`, 정적 메서드 `running()`, `finished(result)`, `notFound()`) |

### 3. `runtime.application.port.AgentRuntime`

```java
RuntimeKind kind();
String submit(RunCommand command);
RunResult awaitCompletion(RunCommand command, String runId);
default RunResult runToCompletion(RunCommand command);   // awaitCompletion(command, submit(command))
void openEvents(RuntimeTarget target, String runId, Consumer<RunEvent> onEvent, Consumer<Closeable> onOpened, ToolDetailScope scope);
void stop(RuntimeTarget target, String runId);
default SteerOutcome steer(RuntimeTarget target, String runId, String text);            // 기본 REJECTED
void deleteSession(RuntimeTarget target, String sessionId);
RunLookup lookupRun(RuntimeTarget target, String runId);
default SessionRuntime readSessionRuntime(RuntimeTarget target, String sessionId);      // 기본 null
default void releaseIfLost(RuntimeTarget target, String runId);                         // 기본 아무것도 하지 않음
default SubagentSessionUsage readSubagentUsage(RuntimeTarget target, String sessionId); // 기본 null
```

Javadoc 에 메서드마다 「하지 못하는 구현이 내는 값」 을 적는다(설계의 port 표): `lookupRun` 은 `NOT_FOUND`, `deleteSession` 은 아무것도 하지 않음, `steer` 는 `REJECTED`. `openEvents` 는 「scope 에 따라 가린 사건만 낸다」 를 계약으로 적는다.

### 4. `runtime.application.AgentRuntimes`

`@Component`. Spring 이 주는 `List<AgentRuntime>` 을 `kind()` 로 묶어 갖고 `AgentRuntime of(RuntimeTarget target)` 로 고른다. 맞는 구현이 없으면 `ApiException` 을 `ErrorCode.HERMES_RUN_FAILED` 로 던진다(Hermes 실행 실패와 같은 길로 실행이 실패로 기록된다). 같은 `kind()` 가 둘이면 생성자에서 `IllegalStateException`.

### 5. `Agent.runtimeTarget()`

`agent/domain/Agent.java` 에 메서드 둘을 더한다.

- `runtimeTarget()`: `new RuntimeTarget(RuntimeKind.HERMES, apiBaseUrl, hermesProfile)`.
- `runtimeTargetOf(String storedUnit)`: `new RuntimeTarget(RuntimeKind.HERMES, apiBaseUrl, storedUnit)`. 이미 보낸 실행을 다시 부를 때 쓴다(에이전트의 profile 이 바뀐 뒤에도 그 실행이 돈 곳으로 간다).

### 6. `hermes.infra` 의 Hermes 구현

- `HermesRunEventKinds`(final, 정적 메서드): `RunEventKind kindOf(String hermesType)` 는 소문자로 바꿔 컨텍스트의 열 이름을 옮기고 모르는 이름과 null 은 `OTHER`. `RunEvent toRunEvent(HermesRunEvent event)` 는 나머지 칸을 그대로 옮긴다. 둘 다 public 이다(phase 03 의 테스트 도우미가 쓴다).
- `HermesRunOutcomes`(final, 정적 메서드): `RunResult toRunResult(HermesRunResult result)`. `statusLabel` 은 `result.status()` 그대로, `providerBlocked` 는 `result.providerBlocked()`, `outcome` 은 `result.succeeded()` 면 `SUCCEEDED`, 지금 부르는 쪽이 취소로 보는 상태면 `CANCELLED`, 나머지는 `FAILED`. 취소로 보는 문자열은 컨텍스트의 세 파일에서 읽은 비교식과 같게 하고 그 출처를 주석에 적는다. `RunLookup toRunLookup(HermesRunLookup lookup)` 도 둔다. public 이다.
- `HermesAgentRuntime`(`@Component`, `AgentRuntime` 구현): `HermesRunsClient` 와 `HermesRunEventStream` 을 주입받는다. `kind()` 는 `HERMES`. `RunCommand` 를 `HermesRunCommand`(profile 은 `target.unit()`, 주소는 `target.location()`)로 옮겨 부르고 결과를 `HermesRunOutcomes` 로 옮긴다. `openEvents` 는 `HermesRunEventStream.open(…)` 에 `event -> onEvent.accept(HermesRunEventKinds.toRunEvent(event))` 를 넘긴다. 그 밖의 로직은 넣지 않는다.

### 7. 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventKindTest.java`(신규): Hermes 사건 이름 열 개가 각각 맞는 `RunEventKind` 로 옮겨진다. 끝 넷은 `terminal()` 이 참이고 나머지는 거짓이다. 모르는 이름(`"tool.unknown"`)과 null 은 `OTHER`. 대문자(`"RUN.COMPLETED"`)도 같은 종류다. 주석에 「Hermes 는 소문자로 보낸다. 옮기기 전 `ChatRunEvents` 의 끝 사건 판정은 대소문자를 가렸다」 를 적는다. `toRunEvent` 가 나머지 칸을 그대로 옮기는지 한 사례로 본다.
- `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunStatusMappingTest.java`(신규): Hermes 의 종료 상태 문자열마다(`hermes/infra/HttpHermesRunsClient.java` 의 `TERMINAL` 과 `hermes/infra/HermesSessionRuns.java` 가 쓰는 종료 상태를 읽어 테스트에 글자로 적는다) `statusLabel` 이 받은 글자 그대로이고, `completed` 는 `SUCCEEDED`, 취소 상태는 `CANCELLED`, 나머지는 `FAILED` 다. 오류 글이 「provider 계정이 막힘」 접두로 시작하면 `providerBlocked` 가 참, 아니면 거짓이다.
- `backend/src/test/java/com/bifos/assistant/runtime/application/port/AgentRuntimeContractTest.java`(신규, 추상 클래스): 구현과 그 구현에 사건·결과를 흘리는 방법을 내주는 추상 메서드를 두고 아래를 본다. 끝 종류 넷을 각각 흘리면 `openEvents` 가 `terminal()` 인 사건을 낸다. scope 가 `ToolDetailScope.ALL` 이면 도구 사건의 `detail` 이 원문으로 오지 않는다. 모르는 실행의 `lookupRun` 은 `NOT_FOUND` 다. `supportsSteer()`(추상, 구현이 답한다)가 거짓인 구현의 `steer` 는 `REJECTED` 이고, 참인 구현은 이 사례를 건너뛴다(`Assumptions`).
- `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesAgentRuntimeContractTest.java`(신규): 위 추상 테스트를 상속한다. `hermes/infra/ToolDetailEventStreamTest` 가 쓰는 가짜 HTTP 서버 방식으로 실제 `HttpHermesRunsClient` 와 `HermesRunEventStream` 을 감싼 `HermesAgentRuntime` 을 만든다. `supportsSteer()` 는 참.
- `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesAgentRuntimeTest.java`(신규, 단위): 가짜 `HermesRunsClient` 로 `submit` 이 `HermesRunCommand` 의 profile 과 주소에 `target.unit()`, `target.location()` 을 넣는지, `stop`, `steer`, `lookupRun`, `deleteSession`, `releaseIfLost`, `readSessionRuntime`, `readSubagentUsage` 가 같은 값을 넘기는지 본다.
- `backend/src/test/java/com/bifos/assistant/runtime/application/AgentRuntimesTest.java`(신규, 단위): 종류에 맞는 구현을 고른다. 구현이 없으면 `ApiException`. 같은 종류가 둘이면 생성이 실패한다.
- `backend/src/test/java/com/bifos/assistant/agent/domain/AgentRuntimeTargetTest.java`(신규, 단위): `runtimeTarget()` 이 에이전트의 주소와 profile 을, `runtimeTargetOf("other-profile")` 이 넘긴 단위를 갖는다.

## 검증

```bash
cd backend && ./gradlew compileJava compileTestJava
cd backend && ./gradlew archTest
cd backend && ./gradlew test --tests 'com.bifos.assistant.hermes.infra.HermesRunEventKindTest' --tests 'com.bifos.assistant.hermes.infra.HermesRunStatusMappingTest' --tests 'com.bifos.assistant.hermes.infra.HermesAgentRuntimeContractTest' --tests 'com.bifos.assistant.hermes.infra.HermesAgentRuntimeTest' --tests 'com.bifos.assistant.runtime.application.AgentRuntimesTest' --tests 'com.bifos.assistant.agent.domain.AgentRuntimeTargetTest'
cd backend && ./gradlew test
git grep -nE "apiBaseUrl|profileName|import com.bifos.assistant.hermes" -- backend/src/main/java/com/bifos/assistant/runtime; test $? -eq 1
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/runtime/domain/RuntimeKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/RuntimeTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunEventKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunLookup.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/port/AgentRuntime.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/AgentRuntimes.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesAgentRuntime.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunEventKinds.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunOutcomes.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventKindTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunStatusMappingTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/runtime/application/port/AgentRuntimeContractTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesAgentRuntimeContractTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesAgentRuntimeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/runtime/application/AgentRuntimesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/domain/AgentRuntimeTargetTest.java` | 신규 |
