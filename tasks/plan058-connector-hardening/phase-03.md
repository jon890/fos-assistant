# Phase 03. Control Plane 이 커넥터 에이전트에 Memory 와 Control Plane 도구를 주지 않는다

**Execution profile**: deep

## 목표

커넥터 에이전트의 실행이 Memory 문맥과 Control Plane MCP 도구를 받지 않게 한다. 외부 서비스의 글이 모델을 속여도 Memory, 결과물 폴더, 다른 에이전트에 닿지 못하게 하기 위해서다.

**범위 외**: 대시보드 plugin 의 목록 쓰기는 phase 02 가 끝냈다. 커넥터 MCP 서버의 쓰기 도구 승인(action policy)과 에이전트 종류 enum 은 다루지 않는다.

## 컨텍스트

- 커넥터 에이전트는 `Agent.connectorManaged()` 가 참인 에이전트다(`backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java`)
- Memory 문맥은 두 곳에서 조립한다. 사용자가 직접 연 대화는 `ChatService` 가 `contextAssembler.assemble(user)` 를 부르고(흐름 없는 turn 경로. 에이전트는 `routed.agent()`), 위임과 흐름의 하위 실행은 `AgentRunner.run` 이 `contextAssembler.assemble(user)` 를 부른다(에이전트는 인자 `agent`)
- 빈 문맥은 `AssembledContext.empty()` 다. `instructions` 가 null, `chars` 가 0, `instructionsHash()` 가 null 이다
- Control Plane MCP 호출의 요청자는 `McpCallerResolver.resolve` 가 정한다. origin 실행은 `AgentExecution` 이고 `agentId()` 를 갖는다. 정하지 못하면 `ErrorCode.MCP_CALL_CONTEXT_INVALID` 하나로 거절하고 이유는 로그에만 남긴다
- 지금 `ConnectorConnectionService.apply` 는 env 를 쓴 뒤 `toolsets.writeApiServer(profile, List.of(AgentToolPolicy.CONTROL_PLANE_MCP))` 를 부르고 설치한다. `narrowedEnabled` 는 켜진 내장 도구가 보이면 `writeApiServer(profile, List.of(CONTROL_PLANE_MCP, mcpServer))` 를 부른다
- phase 02 뒤의 대시보드 plugin 은 설치(`HermesConnectorClient.putConnector(profile, id, true)`)가 API 도구 목록을 커넥터 서버만으로 다시 쓰고, 목록이 옛 모양이면 `readConnector` 가 `configured: false` 를 돌려준다. `putConnector` 의 반환값은 `restart_required` 다
- 위임 결과는 `AgentDelegationService` 가 `AgentRunner` 로 자식을 돌려 실행 줄의 `output_text` 에 적고, `DelegationWakeService` 가 부모 대화의 다음 turn 을 연다. 자식의 MCP 호출을 쓰지 않는다
- 구조 규칙: `mcp` 는 Hermes 를 부르는 타입을 쓰지 않는다(`MCP_DOES_NOT_CALL_HERMES`). `orchestration` 은 `mcp` 를 쓰지 않는다. `mcp` 가 `agent.infra.AgentRepository` 를 쓰는 것은 새 최상위 간선인지 `./gradlew archTest` 로 확인한다. 순환이 생기면 `McpToolService` 가 이미 쓰는 경로(예: `AgentExecutionRepository` 와 같은 층의 조회)를 따른다

**근거 문서**: `docs/adr/ADR-044-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md`, `docs/connectors.md` 의 「커넥터 에이전트의 경계」 와 「설치와 실패 처리」, `docs/flow.md` 의 「커넥터 연결」

## 의도 메모

- 도구마다 판정을 나누지 않는다. 커넥터 에이전트의 Control Plane MCP 호출은 모두 거절한다
- 거절 응답을 새로 만들지 않는다. 서명이 틀린 호출과 같은 `MCP_CALL_CONTEXT_INVALID` 로 답해 밖에서 구분하지 못하게 한다
- 이미 설치한 연결을 새 목록으로 바꾸려고 사용자에게 토큰을 다시 받지 않는다. 연결 확인과 관리자의 반영 완료가 설치를 다시 쓴다
- 일반 에이전트의 Memory 주입과 도구 목록은 바꾸지 않는다

## 작업 항목

### 1. `AgentRunner` 와 `ChatService` 가 커넥터 에이전트에 빈 문맥을 준다

- `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` 의 `run`(12개 인자 판): `AssembledContext context = agent.connectorManaged() ? AssembledContext.empty() : contextAssembler.assemble(user);`. 클래스 Javadoc 의 Memory 문단에 커넥터 에이전트 예외와 ADR-044 를 한 줄로 적는다
- `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 의 `contextAssembler.assemble(user)` 호출 자리: 그 turn 의 에이전트가 `connectorManaged()` 이면 `AssembledContext.empty()` 를 쓴다. `ChatService` 에 `assemble` 호출이 하나뿐인지 `grep -n "contextAssembler" ` 로 확인하고 모두 같은 판정을 지나게 한다
- `instructionAddition` 과 turn 지시(결과물 안내 같은 것)는 그대로 붙는다. Memory 문맥만 뺀다

### 2. `McpCallerResolver` 가 커넥터 에이전트의 호출을 거절한다

- `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` 의 `resolve`: origin 실행을 찾은 뒤 그 실행의 에이전트를 `origin.agentId()` 로 읽는다. 에이전트가 `connectorManaged()` 이면 `reject(toolName, "커넥터 에이전트는 Control Plane 도구를 쓰지 못한다")` 를 던진다. 에이전트 행이 없으면 지금 동작을 유지한다(거절하지 않는다. 지워진 에이전트의 실행은 다른 검사가 다룬다)
- 클래스 Javadoc 에 이 판정과 ADR-044 를 적는다

### 3. `ConnectorConnectionService` 가 도구 목록을 쓰지 않고 설치에 맡긴다

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java`:

- `apply`: `STEP_TOOLSET` 단계와 `toolsets.writeApiServer(...)` 호출을 지운다. env 뒤에 바로 설치한다. 상수 `STEP_TOOLSET` 도 지운다
- `narrowedEnabled` 를 지우고 `usable` 은 `probe.ok() && !probe.tools().isEmpty() && toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).isEmpty()` 로 판정한다
- 새 private 메서드 `reinstall(ConnectorConnection connection)`: `connector.putConnector(profile, connectorId, true)` 를 부르고 `connection.markRestartRequired(...)` 에 그 반환값을 넣는다. 카탈로그에 없는 커넥터면 부르지 않는다(`findManifest` 가 비면 설치가 거절된다)
- `check`: `desiredEnabled` 이고 `state.enabled()` 인데 `!state.configured()` 이면 `reinstall` 을 부른 뒤 `readConnector` 를 다시 읽는다. `usable` 이 거짓이고 켜진 내장 도구가 남아 있을 때도 `reinstall` 을 한 번 부르고 다시 판정한다. 다시 쓴 뒤 `connection.restartRequired()` 이면 `PENDING` 으로 저장하고 돌려준다. 다시 쓰기는 한 요청에 한 번만 한다. 실패하면 지금의 `STEP_INSTALL` 실패와 같이 경고를 남기고 `ConnectorOperationFailure` 다
- `confirmApplied`: 같은 규칙이다. `desiredEnabled` 이고 `state.enabled() && !state.configured()` 이면 `reinstall` 을 부른다. 재시작이 필요해지면 `pending` 으로 두고 `ConnectorOperationFailure` 를 던진다(관리자가 gateway 를 재시작한 뒤 다시 누른다). `noRollbackFor` 라 `restart_required` 는 저장된다
- `HermesToolsetClient` 필드는 `readEnabled` 에 계속 쓴다. `AgentToolPolicy` import 가 쓰이지 않으면 지운다
- 메서드가 60줄을 넘으면 private 메서드로 나눈다(`MethodLength` 경고)

### 4. 이 phase 를 검증하는 backend 테스트

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java`: `writeApiServer` 를 기대하는 검사(156, 745, 785, 814 줄 부근)를 고친다. 등록 순서는 확인 도구, env, 설치다. `toolsets.writeApiServer` 는 어느 경로에서도 불리지 않는다(`verify(toolsets, never()).writeApiServer(any(), any())`). 새 검사 셋을 더한다
  - `checkReinstallsWhenInstallIsNotConfigured`: `readConnector` 가 처음에 `enabled=true, configured=false`, 다시 읽을 때 `configured=true` 이고 `putConnector` 가 false(재시작 불필요)를 돌려주면 `READY` 다
  - `checkStaysPendingWhenReinstallNeedsRestart`: `putConnector` 가 true 를 돌려주면 `PENDING` 이고 `restartRequired` 가 참이다
  - `confirmAppliedReinstallsOldAllowlistAndWaitsForRestart`: 관리자 반영 완료가 `putConnector(profile, id, true)` 를 부르고 `ConnectorOperationFailure` 로 끝나며 저장된 행의 `restartRequired` 가 참이다
- 새 파일 `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java`: `AgentRunnerSubmitFailureTest` 의 대역 구성을 본보기로 쓴다. `connectorManaged` 에이전트로 `run` 하면 `contextAssembler.assemble` 이 불리지 않고 `HermesRunCommand.instructions()` 가 `instructionAddition` 뿐이며(없으면 null) 실행 줄의 문맥 길이가 0 임을 본다. 일반 에이전트는 `assemble` 이 불린다. 위임 키를 준 실행이 성공하면 `output_text` 가 적히는 것도 같은 파일에서 본다(위임 결과 경로가 Memory 없이 동작한다)
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 검사 하나를 더한다. 커넥터 에이전트의 대화 turn 이 Hermes 에 보낸 `instructions` 에 그 사용자의 Memory 본문이 없음을 본다. 그 파일의 기존 Memory 주입 검사를 본보기로 쓴다
- `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallerTest.java` 또는 `McpCallerInvariantTest.java` 가운데 `McpCallerResolver.resolve` 를 직접 검사하는 파일에 검사 둘을 더한다. origin 실행의 에이전트가 커넥터 에이전트이면 `MCP_CALL_CONTEXT_INVALID` 이고, 일반 에이전트이면 요청자가 정해진다

### 5. e2e 대역과 시나리오

- `test/e2e/fake-hermes.ts`: `PUT /api/connectors` 가 설치 때 그 profile 의 도구 목록을 `[DEMO_CONNECTOR 의 mcp_server]` 로, 제거 때 `["no_mcp"]` 로 기록한다. `GET /api/connectors` 의 `configured` 는 지금 조건에 더해 기록한 목록이 커넥터 서버 하나일 때만 참이다. 대역이 받은 호출을 기록하는 방식은 지금 것을 따른다
- `test/e2e/scenarios/connector.ts`: 「통과한 토큰으로 등록하면 PENDING 이고 대역이 확인, env, 도구 목록, 설치 순으로 받는다」 단계를 확인, env, 설치 순으로 고치고, 등록 동안 `PUT /api/config` 가 오지 않았음을 본다. 연결 확인 뒤 대역의 목록이 커넥터 서버 하나임을 본다
- `test/e2e/scenarios/delegation.ts` 는 고치지 않는다. 일반 에이전트의 위임이 그대로 통과해야 한다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*ConnectorConnectionServiceTest' --tests '*AgentRunnerConnectorContextTest' --tests '*ChatServiceTest' --tests '*McpCaller*' --tests '*AgentDelegationService*' --tests '*DelegationWakeServiceTest'
./gradlew archTest
./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
! grep -n "writeApiServer" backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java
scripts/check-public-safe.sh
```

`node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 앞선 실행이 남긴 데이터에 걸린다. 모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallerTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector.ts` | 수정 |
