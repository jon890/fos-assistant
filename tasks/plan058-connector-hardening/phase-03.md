# Phase 03. 커넥터 에이전트의 실행에 Memory 문맥을 주지 않고 Control Plane 도구 호출을 거절한다

**Execution profile**: deep

## 목표

커넥터 에이전트의 실행이 Memory 문맥을 받지 않고, 그 실행에서 온 Control Plane MCP 도구 호출을 Control Plane 이 거절하게 한다. 외부 서비스의 글이 모델을 속여도 Memory, 결과물 폴더, 다른 에이전트에 닿지 못하게 하기 위해서다.

**범위 외**: 설치 흐름과 도구 목록은 다음 phase 가 고친다. MCP 토큰의 발급과 인증(`McpPrincipal`)은 바꾸지 않는다. 커넥터 에이전트 대화의 답이 Memory 제안으로 가는 경로는 다루지 않는다(ADR-044 「다음」).

## 컨텍스트

- 커넥터 에이전트는 `Agent.connectorManaged()` 가 참인 에이전트다(`backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java`)
- Memory 문맥은 두 곳에서 조립한다. 사용자가 직접 연 대화는 `ChatService` 가 `contextAssembler.assemble(user)` 를 부르고(흐름 없는 turn 경로. 에이전트는 `routed.agent()`), 위임과 흐름의 하위 실행은 `AgentRunner.run` 이 `contextAssembler.assemble(user)` 를 부른다(에이전트는 인자 `agent`)
- 빈 문맥은 `AssembledContext.empty()` 다. `instructions` 가 null, `chars` 가 0, `instructionsHash()` 가 null 이다
- Control Plane MCP 호출의 요청자는 `McpCallerResolver.resolve` 가 정한다. `McpController` 의 `tools/call` 이 도구 여섯(`memory_read`, `artifact_write`, `agent_list`, `agent_status`, `agent_delegate`, `agent_stop`) 모두에서 이 메서드를 먼저 지난다. origin 실행은 `AgentExecution` 이고 `agentId()` 를 갖는다. 정하지 못하면 `ErrorCode.MCP_CALL_CONTEXT_INVALID` 하나로 거절하고 이유는 로그에만 남긴다
- `mcp` 는 이미 `agent.domain.Agent` 를 쓰고 `agent` 는 `mcp` 를 쓰지 않는다. `agent.infra.AgentRepository` 를 주입해도 새 최상위 순환이 생기지 않는다
- 위임 결과는 `AgentDelegationService` 가 `AgentRunner` 로 자식을 돌려 실행 줄의 `output_text` 에 적고, `DelegationWakeService` 가 부모 대화의 다음 turn 을 연다. 자식의 MCP 호출을 쓰지 않는다

**근거 문서**: `docs/adr/ADR-044-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md`, `docs/connectors.md` 의 「커넥터 에이전트의 경계」

## 의도 메모

- 도구마다 판정을 나누지 않는다. 지금의 도구 여섯은 모두 `resolve` 를 지나므로 한 곳에서 거절한다
- 거절은 `tools/call` 의 요청자 판정에만 둔다. 토큰 인증 단계에서 커넥터 profile 의 토큰을 통째로 무효로 만들지 않는다. 뒤에 같은 토큰으로 정책을 묻는 다른 경로를 더할 수 있어야 한다
- 거절 응답을 새로 만들지 않는다. 서명이 틀린 호출과 같은 `MCP_CALL_CONTEXT_INVALID` 로 답해 밖에서 구분하지 못하게 한다
- 일반 에이전트의 Memory 주입은 바꾸지 않는다

## 작업 항목

### 1. `AgentRunner` 와 `ChatService` 가 커넥터 에이전트에 빈 문맥을 준다

- `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` 의 `run`(12개 인자 판): `AssembledContext context = agent.connectorManaged() ? AssembledContext.empty() : contextAssembler.assemble(user);`. 클래스 Javadoc 의 Memory 문단에 커넥터 에이전트 예외와 ADR-044 를 한 줄로 적는다
- `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 의 `contextAssembler.assemble(user)` 호출 자리: 그 turn 의 에이전트가 `connectorManaged()` 이면 `AssembledContext.empty()` 를 쓴다. `grep -n "contextAssembler" backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 로 호출이 하나뿐인지 확인하고 모두 같은 판정을 지나게 한다
- `instructionAddition` 과 turn 지시(결과물 안내 같은 것)는 그대로 붙는다. Memory 문맥만 뺀다

### 2. `McpCallerResolver` 가 커넥터 에이전트의 호출을 거절한다

- `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` 에 `AgentRepository` 를 주입한다. `resolve` 는 origin 실행을 찾은 뒤 `agents.findById(origin.agentId())` 로 에이전트를 읽는다. 에이전트가 `connectorManaged()` 이면 `reject(toolName, "커넥터 에이전트는 Control Plane 도구를 쓰지 못한다")` 를 던진다. `agentId` 가 null 이거나 에이전트 행이 없으면 거절하지 않고 지금 동작을 유지한다
- 클래스 Javadoc 에 이 판정과 ADR-044 를 적는다

### 3. 이 phase 를 검증하는 backend 테스트

- 새 파일 `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java`: `AgentRunnerSubmitFailureTest` 의 대역 구성을 본보기로 쓴다. `connectorManaged` 에이전트로 `run` 하면 `contextAssembler.assemble` 이 불리지 않고 `HermesRunCommand.instructions()` 가 `instructionAddition` 뿐이며(없으면 null) 실행 줄에 넘긴 문맥 길이가 0 임을 본다. 일반 에이전트는 `assemble` 이 불린다. 위임 키를 준 실행이 성공하면 답이 `output_text` 로 넘어감도 같은 파일에서 본다(위임 결과 경로가 Memory 없이 동작한다)
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 검사 하나를 더한다. 커넥터 에이전트의 대화 turn 이 Hermes 에 보낸 `instructions` 에 그 사용자의 Memory 본문이 없음을 본다. 그 파일의 기존 Memory 주입 검사를 본보기로 쓴다
- 새 파일 `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallerResolverTest.java`: `SessionOwnerResolver`, `AppUserRepository`, `AgentRepository` 를 mock 으로 두고 `McpCallerResolver` 를 직접 만든다. 서명이 맞는 `_fos_ctx` 는 `McpCallContextTest` 가 만드는 방식(또는 `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java`)을 쓴다. 검사는 셋이다. origin 실행의 에이전트가 커넥터 에이전트이면 `MCP_CALL_CONTEXT_INVALID` 다. 일반 에이전트이면 `McpCaller` 의 사용자가 origin 실행의 사용자다. 에이전트 행이 없으면 거절하지 않는다
- `McpCallerResolver` 를 직접 만드는 다른 검사가 있으면 생성자 인자를 맞춘다. `git grep -n "new McpCallerResolver(" backend/src/test` 로 찾는다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*AgentRunnerConnectorContextTest' --tests '*ChatServiceTest' --tests '*McpCallerResolverTest' --tests '*Mcp*' --tests '*AgentDelegationService*' --tests '*DelegationWakeServiceTest'
./gradlew archTest
./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 앞선 실행이 남긴 데이터에 걸린다. 일반 에이전트의 위임 시나리오가 그대로 통과해야 한다. 모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallerResolverTest.java` | 신규 |
