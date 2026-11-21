# Phase 03. 실행을 부르는 곳을 AgentRuntimes 로 바꾼다

**Execution profile**: standard

## 목표

`hermes` 밖의 운영 코드가 `HermesRunsClient`, `HermesRunEventStream` 과 Hermes 의 실행 타입(`HermesRunCommand`, `HermesRunResult`, `HermesRunLookup`, `HermesRunEvent`)을 쓰지 않고, `AgentRuntimes` 로 고른 `AgentRuntime` 과 `runtime` 의 중립 타입을 쓰게 한다. **사용자에게 보이는 동작과 저장되는 값은 바뀌지 않는다.**

**범위 외**: 구조 규칙과 문서(phase 04). Hermes 쪽 코드(`hermes/`)는 고치지 않는다.

## 컨텍스트

`HermesRunsClient` 를 주입받는 `hermes` 밖의 운영 코드(2026-11-21, `backend/src/main/java/com/bifos/assistant/` 아래):

- `chat/recovery/application/RestartReconciler.java`
- `chat/room/application/RoomContext.java`
- `chat/purge/application/ConversationPurger.java`
- `chat/turn/application/ChatRunEvents.java`(`HermesRunEventStream` 도 주입받는다)
- `chat/turn/application/TurnCancellation.java`
- `chat/starter/application/StarterSuggestionService.java`
- `chat/nextturn/application/SteerService.java`
- `usage/execution/application/LostRunRelease.java`
- `usage/execution/application/ExecutionCompletionRecorder.java`
- `usage/execution/application/UserExecutionLimiter.java`
- `usage/subagent/application/SubagentUsageReconciler.java`
- `orchestration/application/AgentRunner.java`
- `orchestration/application/AgentDelegationService.java`
- `orchestration/application/DelegationRuns.java`

Hermes 의 실행 타입만 쓰는 곳도 있다(`chat/recovery/application/RecoveredRunRecorder.java`, `chat/turn/application/ChatTurnRunner.java`, `chat/turn/application/ChatTurnLifecycle.java`, `chat/turn/application/dto/PendingTurn.java`, `usage/execution/application/ExecutionEventRecorder.java` 등). 구현 전에 아래로 전부 찾는다.

```bash
git grep -lE "HermesRunsClient|HermesRunEventStream|HermesRunCommand|HermesRunResult|HermesRunLookup|HermesRunEvent" -- backend/src/main/java | grep -v "/hermes/"
```

중립 타입과 옮기는 규칙은 phase 02 가 만들었다: `runtime/application/port/AgentRuntime.java`, `runtime/application/AgentRuntimes.java`, `runtime/application/dto/`(`RunCommand`, `RunEvent`, `RunEventKind`, `RunResult`, `RunOutcome`, `RunLookup`), `agent/domain/Agent.java` 의 `runtimeTarget()`, `runtimeTargetOf(String)`.

**요구사항**: REQ1

**근거 문서**: `docs/features/runtime-abstraction/design.md` 의 「대상을 만드는 두 길」, 「그대로 두는 Hermes 전용 자리」, 「실패와 일관성」

## 의도 메모

- **대상의 출처를 바꾸지 않는다.** 지금 `agent.apiBaseUrl()` 과 `agent.hermesProfile()` 을 넘기는 자리는 `agent.runtimeTarget()` 으로, 저장된 profile 이름을 넘기는 자리는 `agent.runtimeTargetOf(저장된 값)` 으로 바꾼다. 둘을 섞으면 에이전트의 profile 이 바뀐 뒤 복구, 위임 중지, 대화 삭제, 사용량 정리가 다른 profile 로 간다.
- 부르는 쪽은 `target.unit()` 을 저장하고, 같은지 비교하고, 그 값으로 조회해도 된다. 주소를 조립하거나 Hermes 를 직접 부르는 데 쓰지 않는다.
- 하위 에이전트 사용량 보정(`SubagentUsageReconciler` 가 `hermes.infra.SubagentProviderClient`, `hermes.infra.ProfileModelDefaultsClient` 를 부르는 부분)은 Hermes 전용으로 남긴다(설계 DECISION7). 이 파일에서 바꾸는 것은 `HermesRunsClient.readSubagentUsage` 호출뿐이다.
- 실행 한도 설정(`hermes.config.HermesProperties`)을 읽는 자리는 그대로 둔다.
- 스레드, 잠금, 예외 처리, 로그 문구는 건드리지 않는다.
- commit 마다 도는 크기 hook 이 운영 코드가 상한의 80% 이상이라고 알리면: 스스로 끝나는 호출자(`StarterSuggestionService`, `RoomContext`, `SubagentUsageReconciler`, `ConversationPurger`)를 이 PR 에서 빼고 옛 port 에 둔 채 phase 04 와 함께 다음 PR 로 미룬다. 그때 `index.json` 의 `deferred` 와 PR 본문 「남은 범위」 에 적고 코디네이터에게 알린다.

## 작업 항목

### 1. 주입과 호출

위 클래스에서 `HermesRunsClient` 필드를 `AgentRuntimes` 로 바꾸고 호출마다 `runtimes.of(target).<메서드>(target, …)` 로 부른다. `submit`, `awaitCompletion`, `runToCompletion` 은 `runtimes.of(command.target())` 이다.
`HermesRunCommand` 를 만드는 넷(`RoomContext`, `StarterSuggestionService`, `ChatTurnLifecycle`, `AgentRunner`)은 `RunCommand` 를 만들고 `agent.runtimeTarget()` 을 넘긴다. `command.profileName()`, `command.apiBaseUrl()` 을 읽던 곳은 `command.target()` 을 넘기거나 `command.target().unit()` 을 읽는다.

### 2. 대상의 출처

| 자리 | 지금 넘기는 값 | 바꾼 뒤 |
| --- | --- | --- |
| `RestartReconciler` 의 조회·중지·정리 | `agent.apiBaseUrl()` 과 실행 기록의 `profileName()` | `agent.runtimeTargetOf(row.profileName())` |
| `AgentDelegationService` 의 위임 중지 | `agent.apiBaseUrl()` 과 `execution.profileName()` | `agent.runtimeTargetOf(execution.profileName())` |
| `ConversationPurger` 의 세션 삭제 | 세션 참조(`ExecutionSessionRef`)의 `profileName()` | `agent.runtimeTargetOf(ref.profileName())`. profile 과 세션 번호의 짝으로 중복을 빼는 로직은 그대로 둔다 |
| `SubagentUsageReconciler` 의 사용량 읽기 | 작업 줄(`SubagentUsageJob`)에 저장한 주소와 profile | `new RuntimeTarget(RuntimeKind.HERMES, 저장한 주소, 저장한 profile)`. 「profile 이 바뀜」 판정은 그대로 둔다 |
| `LostRunRelease` | `command.profileName()` 으로 조회 | `command.target().unit()` 으로 조회 |
| 그 밖(새 실행을 보내는 곳, 도는 turn 의 중지와 글 끼우기) | `agent.apiBaseUrl()`, `agent.hermesProfile()` 또는 그것으로 만든 `command` | `agent.runtimeTarget()` 또는 `command.target()` |

`UserExecutionLimiter.holdUntilRemoteEnds(Long userId, Long executionId, String apiBaseUrl, String profileName, String runId, boolean stopAlreadySent)` 의 문자열 두 인자를 `RuntimeTarget target` 하나로 바꾼다. 부르는 여섯 곳은 지금 넘기는 값의 출처를 지킨다: `RestartReconciler` 는 `agent.runtimeTargetOf(row.profileName())`, `RoomContext`, `ChatRunEvents`, `StarterSuggestionService`, `AgentRunner`(두 곳)는 `command.target()` 또는 `agent.runtimeTarget()`(지금 넘기는 값이 command 의 것인지 agent 의 것인지 읽고 같은 쪽을 쓴다).

실행 기록의 profile 이름에 적는 `usage/execution/application/ExecutionRecorder.java` 의 `agent.hermesProfile()` 은 `agent.runtimeTarget().unit()` 으로 바꾼다. 적히는 값은 같다.

### 3. 주소와 profile 을 들고 다니는 타입

`chat/turn/application/TurnRunRef.java` 와 `orchestration/application/RunningDelegation.java` 가 가진 주소와 profile 문자열 두 칸을 `RuntimeTarget` 하나로 바꾸고, 만드는 곳과 읽는 곳(`TurnHandle`, `ChatTurnStopper`, `SteerService`, `TurnCancellation`, `AgentRunner`, `DelegationRuns`)을 따라 바꾼다.

### 4. 사건

- `ChatRunEvents`: `HermesRunEventStream` 주입을 없애고 `runtimes.of(target).openEvents(target, runId, onEvent, onOpened, detailScope)` 를 부른다. `TERMINAL_EVENTS.contains(event.type())` 은 `event.kind().terminal()` 로, `forward` 와 `append` 와 하위 에이전트 세기의 문자열 비교는 `kind` 비교로 바꾼다. 분기는 지금 if 줄의 순서와 조건을 그대로 두고 비교 대상만 바꾼다. `OTHER` 는 지금 모르는 이름처럼 아무 분기에도 들지 않는다.
- `ExecutionEventRecorder`: Hermes 이름 → `ExecutionEventType` 표의 키를 `RunEventKind` 로 바꾼다. 표에 없는 종류를 버리는 동작은 그대로다.

### 5. 끝난 결과

- 취소인지 보는 곳(`ChatTurnRunner`, `AgentRunner`, `RecoveredRunRecorder`): `result.outcome() == RunOutcome.CANCELLED`.
- 상태 문자열을 대문자로 바꿔 실행 기록의 오류 코드로 저장하거나 로그에 남기는 곳(`ChatTurnRunner`, `AgentRunner`, `StarterSuggestionService`, `RoomContext`, `RecoveredRunRecorder`): `result.status()` 를 `result.statusLabel()` 로만 바꾼다. 대문자로 바꾸는 코드와 null 처리는 그대로 둔다.
- `result.providerBlocked()`, `result.succeeded()` 는 이름이 같아 그대로다.
- `RecoveredRunRecorder` 와 `RestartReconciler` 가 `HermesRunLookup` 의 상태를 읽는 곳은 `RunLookup.State` 로 바꾼다.

### 6. 테스트

- 부르는 쪽 클래스를 `new` 로 직접 만드는 단위 테스트는 `HermesRunsClient` 대신 `AgentRuntimes` 를 넘긴다. 조립은 새 도우미 `backend/src/test/java/com/bifos/assistant/testsupport/TestAgentRuntimes.java` 의 `TestAgentRuntimes.hermes(HermesRunsClient client, HermesRunEventStream stream)`(`new AgentRuntimes(List.of(new HermesAgentRuntime(client, stream)))`) 한 곳에 둔다. 가짜 `HermesRunsClient` 가 돌려주는 `HermesRunResult` 와 그 상태 문자열은 고치지 않는다.
- 통합 테스트의 `StubHermesRunsClient` 와 `@MockitoBean HermesRunEventStream`, 그 준비 코드는 그대로 둔다. 실제 `HermesAgentRuntime` 빈이 둘을 부른다.
- `Agent` 를 `mock(Agent.class)` 로 만드는 테스트(예: `orchestration/application/AgentDelegationServiceRaceTest`)는 `runtimeTarget()` 과 `runtimeTargetOf(...)` 가 null 을 내지 않게 stub 한다(지금 stub 한 `apiBaseUrl()`, `hermesProfile()` 값으로 만든 `RuntimeTarget`).
- `usage` 의 `ExecutionEventRecorderTest` 처럼 recorder 에 사건을 직접 넣는 테스트와, 실제 스트림의 사건을 recorder 에 넣는 `SkillViewStreamTest` 는 `HermesRunEventKinds.toRunEvent(hermesEvent)` 로 옮겨 넣는다. Hermes 사건 이름 문자열과 단언은 그대로 둔다.
- `holdUntilRemoteEnds`, `TurnRunRef`, `RunningDelegation` 을 직접 쓰는 테스트는 인자만 `RuntimeTarget` 으로 바꾼다.
- **출처를 지키는지 보는 사례를 더한다.** 에이전트의 지금 profile 과 저장된 profile 이 다른 상황을 만들고 저장된 쪽으로 가는지 본다. `RestartReconciler` 의 실행 조회(실행 기록의 profile), `ConversationPurger` 의 세션 삭제(세션 참조의 profile), `AgentDelegationService` 의 위임 중지(실행 기록의 profile) 각각 지금 있는 테스트 클래스에 사례 하나씩. 가짜 `HermesRunsClient` 가 받은 profile 인자를 단언한다. `runtimeTargetOf` 를 `runtimeTarget` 으로 되돌리면 실패해야 한다.

## 검증

```bash
cd backend && ./gradlew compileJava compileTestJava
cd backend && ./gradlew archTest
cd backend && ./gradlew test
git grep -lE "HermesRunsClient|HermesRunEventStream|HermesRunCommand|HermesRunResult|HermesRunLookup|HermesRunEvent" -- backend/src/main/java | grep -v "/hermes/"; test $? -eq 1
git diff origin/main --stat -- backend/src/main/java/com/bifos/assistant/hermes
```

넷째 줄은 `hermes` 밖의 운영 코드에 Hermes 실행 타입이 남지 않았는지 본다(찾은 것이 없어야 한다. 의도 메모대로 호출자를 다음 PR 로 미뤘으면 그 파일만 남는다).
다섯째 줄의 출력에는 phase 01 과 02 가 바꾼 파일만 있어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TestAgentRuntimes.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
