# Phase 02. 맡긴 실행의 도구 사건을 기록하고 대화 SSE 로 보낸다

**Execution profile**: deep

## 목표

`AgentRunner` 가 맡긴 실행도 Hermes 사건 스트림을 열어 도구 사건을 그 자식 실행 줄에 남기고, 도는 동안의 사건을 대화 단위 SSE 의 `delegation_activity` 로 보낸다.

**범위 외**: 화면에서 그리는 일(phase 03), e2e(phase 04).

## 컨텍스트

**요구사항**: REQ3

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ3, DECISION2, DECISION21), `docs/features/assistant-hub/design.md` 의 「PR1 맡긴 실행의 사건」, `docs/features/execution.md` 의 「사건 옮겨 적기」, `docs/features/chat.md` 의 「화면으로 보내는 사건」

- 따를 본보기는 대화 turn 의 한 번에 받는 경로 `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatRunEvents.java` 다. `submit`(관측 시작 `executions.beginEventObservation`), `relay`(스트림을 `BackgroundTasks` 스레드에서 `HermesRunEventStream.open` 으로 읽고, `ToolDetailScope` 를 넘기고, 끝에 `finishEventObservation(execution, terminalSeen)`), `store`(실행마다 counter 로 순서 번호를 정한다)를 읽는다.
- `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` 는 지금 `hermes.submit` 뒤 `hermes.awaitCompletion` 만 하고, 사건을 `append(execution, type, detail, sequence)` 로 시작 1, 끝 2 를 직접 번호 매겨 적는다. 스트림 사건과 번호가 겹치면 `(execution_id, sequence)` 유일 제약에 걸려 사건이 조용히 사라진다.
- 연결 도구 가리기는 `ToolDetailScope.prefixes(connectorBindings.connectorToolPrefixes(agent.id())).withTreeHistory(at -> connectorCalls.calledBefore(treeRootId, at))` 이다(`ChatRunEvents.toolDetailScope` 와 `relay`).
- 대화 SSE 로 보내는 본보기는 `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationLineNotifier.java`(`DelegationStarted` 를 받아 `ConversationEventHub.publish`)다. `orchestration` 은 `chat` 의 서비스를 부르지 않고 `chat.nextturn.application.dto` 의 사건만 낸다(`DelegationRuns.publishStarted`).
- 보는 사람마다의 가리기는 구독할 때 `ChatEvent.forViewer`(`backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/ChatEvent.java`)가 한다. 지금은 `type == "tool"` 만 `detail` 을 가린다.
- RunEvent 를 화면용 `ChatEvent.tool(...)` 로 바꾸는 일과 플러그인 이름(`ConnectorToolNames`) 붙이기는 `ChatRunEvents.forward` 가 한다.

## 의도 메모

- 웹이 트리를 주기적으로 읽는 안은 기각했다(설계 DECISION3). 도는 답은 흘러온 사건으로 그린다.
- 리스너에서 `forViewer` 를 부르지 않는다. 리스너는 보는 사람을 모른다.
- 스트림이 실패해도 실행은 이어 가고 사건만 없다(ADR-090).

## 작업 항목

### 1. 새 `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunEvents.java`

`@Component`. `AgentRunner` 가 쓰는 사건 기록을 모은다. 파일 길이 상한 때문에 `AgentRunner` 에 넣지 않는다.

- 실행마다 쓰는 counter(1 부터)를 담는 작은 상태 객체 `Recording` 을 만든다. 시작, 스트림 사건, 끝 사건을 모두 이 counter 로 적는다. 저장 실패는 지금처럼 경고 로그만 남기고 counter 를 올리지 않는다(`ChatRunEvents.store` 와 같다).
- `Recording start(...)`: 관측 시작(`executions.beginEventObservation`)을 적고 counter 와 닫힘 표시를 가진 `Recording` 을 돌려준다. `AgentRunner` 가 제출 전에 부른다.
- `void relay(Recording, Agent, AgentExecution, String runId, Conversation)`: **비동기다.** `BackgroundTasks.start("child-event-stream-" + runId, ...)` 스레드에서 `HermesRunEventStream.open` 으로 스트림을 읽고 곧바로 돌아온다. `AgentRunner` 의 실행 스레드는 지금처럼 `hermes.awaitCompletion` 으로 끝을 정한다. 스트림 열기에 넘기는 `ToolDetailScope` 는 `ToolDetailScope.prefixes(connectorBindings.connectorToolPrefixes(agent.id())).withTreeHistory(at -> connectorCalls.calledBefore(execution.treeRootId(), at))` 다. 스트림을 받으면 그 참조를 `Recording` 에 둔다.
- 기록 동기화: 시작, 스트림 사건, 끝 사건의 기록과 counter 증가는 모두 `synchronized (recording)` 안에서 한다(`ChatRunEvents` 의 `synchronized (pending)` 와 같다). `void end(Recording, ExecutionEventType, String detail)` 는 그 안에서 끝 사건을 적고 닫힘 표시를 세운 뒤 스트림을 닫는다. 닫힌 뒤 온 스트림 사건은 적지도 알리지도 않는다(설계 「끝난 뒤 온 사건은 버린다」). 중지된 자식도 `AgentRunner` 가 `RUN_CANCELLED` 를 `end` 로 적으므로 같은 길로 스트림이 닫힌다.
- 스트림 사건을 적은 뒤 도구 사건(`ChatRunEvents.forward` 가 `tool` 로 바꾸는 종류)이고 대화가 있으면 `events.publishEvent(new DelegatedRunActivity(conversation.id(), execution.treeRootId(), execution.id(), event))` 를 낸다. 알리다 난 예외는 경고 로그만 남긴다.
- 관측 끝: 스트림이 끝나면 `finishEventObservation(execution, terminalSeen)`, 스트림이 예외로 끝나면 `false`. 끝 사건을 적을 때 스트림이 아직 열려 있었으면 `false`.

### 2. `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java`

- 고정 번호 1, 2 를 지우고 `ChildRunEvents` 의 counter 로 적는다.
- 고정 번호 인자를 받는 `append(execution, type, detail, sequence)` 를 지우고, 시작은 `childRunEvents.start` 뒤 제출에 성공했을 때 counter 로, 끝(완료, 실패, 취소)은 `childRunEvents.end` 로 적는다.
- 제출 뒤 `awaitCompletion` 전에 `childRunEvents.relay(...)` 를 부른다(곧 돌아온다). 대화가 없는 실행(`conversation == null`)은 SSE 알림만 내지 않는다.
- 모델 해석 실패처럼 제출 전에 끝나는 경로도 `end` 로 적는다.

### 3. 새 `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/dto/DelegatedRunActivity.java`

`record DelegatedRunActivity(Long conversationId, Long rootExecutionId, Long executionId, RunEvent event)`. Javadoc 에 「맡긴 실행의 도구 사건 하나. 대화 화면의 도는 답에 맡은 에이전트의 도구 줄로 그린다」.

### 4. 새 `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifier.java`

`DelegationLineNotifier` 와 같은 꼴. `@EventListener onActivity(DelegatedRunActivity)`: 대화의 공개 식별자를 읽고, `DelegationLines.directDelegation(executionId)`(공개 메서드, `Optional<DelegationLine>`)가 값을 줄 때만 새 `DelegationActivityEvents.of(...)` 가 만든 `ChatEvent` 를 `ConversationEventHub.publish` 한다. 손자 위임의 사건은 보내지 않는다(스펙 에이전트 팀 DECISION18 과 같다). 플러그인 이름은 `ChatRunEvents.forward` 와 같은 조회(`ConnectorToolNames`)로 붙인다. 공통 부분이 있으면 작은 함수로 빼서 둘이 같이 쓴다.

### 5. 새 `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityEvents.java` 와 `ChatEvent.java`

`ChatEvent.java` 는 497줄이라 상한 500줄 안에서 고친다.

- 새 클래스의 정적 메서드 `ChatEvent of(UUID conversationId, Long executionId, DelegationLine delegation, String toolName, String detail, String phase, Long durationMs, Boolean failed, ConnectorToolName connector)` 가 공개 생성자로 `type = "delegation_activity"` 사건을 만든다. 맡은 에이전트 이름표는 `delegation` 칸에 넣는다.
- `ChatEvent.forViewer` 의 첫 조건 `"tool".equals(type)` 을 `("tool".equals(type) || "delegation_activity".equals(type))` 로 바꾼다. 다른 줄은 늘리지 않는다. 클래스 Javadoc 의 칸 설명은 바꾸지 않고, 새 종류의 설명은 `DelegationActivityEvents` 의 Javadoc 에 둔다.

### 5-1. 트리의 위임 표시

`backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/ExecutionNode.java` 에 `boolean delegated`(실행 줄의 `delegation_key` 가 있다)를 더하고 `ExecutionTreeService` 가 채운다. 화면이 도는 맡긴 실행을 「맡겨 둠」 으로 그리는 데 쓴다(phase 03). `ExecutionNode` 를 만드는 다른 곳(`grep -rn "new ExecutionNode(" backend/src`)도 고친다.

### 6. 문서

- `docs/features/execution.md` 「사건 옮겨 적기」 의 「다른 에이전트에게 맡긴 실행은 현재 Hermes 사건 스트림을 열지 않아 도구 사건이 남지 않는다」 를 「맡긴 실행도 같은 스트림을 열어 그 자식 실행 줄에 남긴다. 이 변경 전에 돈 맡긴 실행은 사건이 없다」 로 고친다.
- `docs/features/chat.md` 「화면으로 보내는 사건」 에 `delegation_activity`(맡긴 실행의 도구 사건, 대화 단위 SSE, `tool` 과 같은 가리기)를 더한다.

### 7. 테스트

- 새 `backend/src/test/java/com/bifos/assistant/orchestration/application/ChildRunEventsTest.java`(대역): 시작, 도구 사건 둘, 끝이 1-4 로 빈틈없이 적힌다. `end` 뒤에 온 사건은 적히지 않고 알림도 없다. 스트림 열기에 넘긴 `ToolDetailScope` 가 그 에이전트의 연결 도구 접두사를 가린다(`ToolDetailScope` 의 판정 메서드로 단언한다. 실제 가리기는 `HermesRunEventStream` 이 하므로 대역 스트림으로 보지 않는다). 스트림이 예외로 끝나도 끝 사건이 적힌다. 도구 사건마다 `DelegatedRunActivity` 가 나간다.
- `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java`, `AgentRunnerConnectorContextTest.java`: 새 의존을 넣어 지금처럼 통과한다. 실패 경로의 번호가 counter 로 적힌다.
- 가리기: `DelegationActivityNotifierTest` 에서 `delegation_activity` 의 `detail` 이 `MEMBER` 에게 비고 `ADMIN` 에게 보인다.
- 새 `backend/src/test/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifierTest.java`: 직접 위임의 사건만 publish 하고 손자 위임은 보내지 않는다.
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java`: 위임 키가 있는 자식의 `delegated` 가 참이고 루트는 거짓이다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ChildRunEventsTest' --tests '*AgentRunnerSubmitFailureTest' --tests '*AgentRunnerConnectorContextTest' --tests '*DelegationActivityNotifierTest' --tests '*AgentDelegationServiceTest' --tests '*ArchitectureRules*' --tests '*ExecutionTreeServiceTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-file-length.mjs
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunEvents.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunRecording.java` | 신규 (구조 규칙이 서비스의 공개 중첩 타입을 막아 기록 상태를 따로 둔다) |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/dto/DelegatedRunActivity.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifier.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityEvents.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/ExecutionNode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/tree/application/ExecutionTreeService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/application/ChildRunEventsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifierTest.java` | 신규 |
| `docs/features/execution.md` | 수정 |
| `docs/features/chat.md` | 수정 |
