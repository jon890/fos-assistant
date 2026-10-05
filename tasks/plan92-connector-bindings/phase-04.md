# Phase 04. 먼저 살펴보기가 붙은 연결의 도구를 직접 부른다

**Execution profile**: standard

## 목표

연결이 붙은 에이전트도 먼저 살펴보기를 시작할 수 있게 하고, 살펴보기 turn 이 붙은 연결의 도구를 직접 부르도록 지시를 바꾼다.
커넥터 도구 판정의 살펴보기 경계(읽기 전용이면 `READ` 이면서 승인 없는 도구만, 쓰기 허용이면 나머지를 승인 카드로)는 그대로 직접 호출에 걸린다.

**범위 외**: e2e 시나리오(phase 06). ADR-080 과 ADR-082 의 대체 표시(phase 07).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/backend/proactive-check.md`

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md` 의 「대체된 부분」 가운데 ADR-080 과 ADR-082
- 지금 흐름: `docs/backend/proactive-check.md` 의 「시작 전 점검」, 「읽기 경계」, 「분야 지침이 지킬 것」(커리어 커넥터에 「맡기는」 읽기 도구 셋)
- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckReadiness.java`
  - `ALLOWED_TOOLSETS` 는 `web`, `vision`, `todo`, `skills`, Control Plane MCP 다
  - `allowed(name, writesAllowed)` 는 쓰기 허용이면 `AgentToolPolicy.isKnown(name)` 이나 Control Plane MCP 만 받는다. 그래서 커넥터 서버 이름은 두 경우 모두 `TOOLSETS_NOT_ALLOWED` 다
  - `agent.connectorManaged()` 면 `AGENT_NOT_SUPPORTED`
- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` 의 `COMMON_RULES` 에 「다른 에이전트에는 연결한 서비스의 에이전트에만 필요한 질의를 맡기고, agent_status 의 wait_seconds 로 기다린다.」 가 있다. `WRITES_RULE` 은 「연결한 서비스에 쓰는 일은 사용자 승인을 기다린다.」 를 갖는다
- `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` 의 `delegate` 는 살펴보기 트리에서 요청자의 옛 커넥터 에이전트(`connectorManaged() && owner == user`)에만 맡기게 한다
- `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` 의 `CHECK_TARGET` 글은 「먼저 살펴보기에서는 연결한 서비스의 에이전트에만 맡길 수 있습니다.」 다
- phase 02 와 03 이 만든 port: `agent/application/AgentConnectorBindings.java` 의 `connectorServers(Long agentId)`
- 층 순서: `proactive`(12)는 `agent`(5)를 쓸 수 있다

## 의도 메모

- 커넥터 서버의 도구는 Control Plane 이 호출마다 판정하므로 살펴보기의 읽기 경계를 깨지 않는다. 그래서 붙은 커넥터 서버 이름은 시작 전 점검에서 받는다. 붙지 않은 다른 MCP 서버는 지금처럼 막는다
- 옛 커넥터 에이전트로의 위임은 그 에이전트가 지워질 때까지 남긴다. 지시는 더는 위임을 권하지 않는다
- 위임 상한과 `wait_seconds` 는 바꾸지 않는다

## 작업 항목

### 1. `ProactiveCheckReadiness`

`check(Agent agent)` 가 `AgentConnectorBindings.connectorServers(agent.id())` 를 읽어, 그 이름은 쓰기 허용과 상관없이 허용한다. 붙지 않은 모르는 이름은 지금처럼 `TOOLSETS_NOT_ALLOWED` 다. 클래스 Javadoc 에 까닭을 적는다.

### 2. `ProactiveCheckRun`

- `COMMON_RULES` 의 위임 줄을 「연결한 서비스의 도구는 직접 부른다. 읽기만 하는 실행에서는 조회 도구만 쓸 수 있다.」 로 바꾼다
- `WRITES_RULE` 은 그대로다
- 이 글은 `docs/backend/proactive-check.md` 의 「Control Plane 지시」 와 같아야 한다. 둘을 함께 고친다

### 3. 위임 거절 글

`McpToolService` 의 `CHECK_TARGET` 글을 「먼저 살펴보기에서는 다른 에이전트에 맡기지 않아요. 연결한 서비스의 도구를 직접 부르세요.」 로 바꾼다. `AgentDelegationService` 의 판정은 바꾸지 않는다. 주석에 옛 커넥터 에이전트를 위한 규칙이라고 적는다.

### 4. `docs/backend/proactive-check.md`

- 「시작 전 점검」 에 붙은 커넥터 서버를 받는다는 것
- 「읽기 경계」 표의 커넥터 판정 줄을 「살펴보기 turn 이 직접 부르거나 옛 커넥터 에이전트가 부른 커넥터 도구」 로 고친다. 위임 줄에 옛 커넥터 에이전트에만 남는다고 적는다
- mermaid 그림의 「커넥터 에이전트」 참여자를 「붙은 커넥터 서버」 로 바꾸고 `agent_delegate` 와 `wait_seconds` 단계를 직접 호출로 바꾼다
- 「분야 지침이 지킬 것」 의 「커리어 커넥터에 맡기는 질의」 를 「살펴보기 turn 이 직접 부르는 커리어 커넥터의 읽기 도구」 로 고친다
- 「Control Plane 지시」 를 2와 같게 고친다

### 5. 시험

- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckReadinessTest.java`(수정): 붙은 커넥터 서버가 켜진 에이전트는 읽기 전용과 쓰기 허용 모두 시작할 수 있다. 붙지 않은 MCP 서버는 여전히 `TOOLSETS_NOT_ALLOWED`. 옛 커넥터 에이전트는 `AGENT_NOT_SUPPORTED`
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java`(수정): 살펴보기 turn 의 지시에 「연결한 서비스의 도구는 직접 부른다」 가 있고 위임 줄이 없다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java`(수정): 살펴보기 turn 이 직접 부른 커넥터 쓰기 도구가 읽기 전용이면 `READ_ONLY_RUN`, 쓰기 허용이면 승인 줄이 된다

## 검증

```bash
cd backend && ./gradlew test --tests '*ProactiveCheckReadinessTest' --tests '*ProactiveCheckTurnTest' --tests '*ConnectorPolicyEndpointTest'
cd backend && ./gradlew test
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckReadiness.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckReadinessTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
| `docs/backend/proactive-check.md` | 수정 |
