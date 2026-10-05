# Phase 03. 판정과 승인 실행, 에이전트의 공개와 삭제, 실행 기록 가림

**Execution profile**: deep

## 목표

커넥터 도구 호출의 판정과 승인한 호출의 실행을 「실행의 에이전트에 붙은 바인딩」 으로 한다.
연결이 붙은 에이전트는 그룹에 공개하지 못하고, 지우면 바인딩을 먼저 뗀다. 옛 커넥터 에이전트도 지울 수 있다.
붙은 커넥터 서버의 도구 내용만 실행 기록에서 가린다. 도구 저장과 스킬 게시가 붙은 커넥터 서버 이름을 함께 보낸다. 마지막으로 연결 엔티티에서 옛 칸의 매핑을 뺀다.

**범위 외**: 먼저 살펴보기의 시작 전 점검과 위임 대상(phase 04). HTTP 경로와 화면(phase 05). 판정의 살펴보기 경계(`checkBoundary`)는 바꾸지 않는다. 그대로 직접 호출에 걸린다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/backend/connector-tool-policy.md`, `docs/connectors.md` 의 「승인」

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md` 의 「판정」, 「승인한 호출의 실행」, 「공개 범위」, 「결과의 신뢰 경계」
- 판정: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` 의 `decide(profileName, rootSessionId, sessionId, toolCallId, hermesTool, toolName, argsJson)`. 지금은 `connections.findByAgentId(origin.agentId())` 로 연결 하나를 찾고 `connection.agent().hermesProfile()` 을 토큰의 profile 과 견준다. `replayed` 의 `stillReady(Long agentId)`, `ownServerTool(manifest, hermesTool)`, `confirmedTool(...)`, `granted(connection, confirmedTool, now)` 가 있다
- 판정 순수 함수: `connector/domain/ToolPolicyDecision.java` 의 `decide(ConnectionStatus, boolean ownServerTool, int schema, Optional<ToolPolicy>, boolean granted, int argsBytes, CheckBoundary)`
- 줄: `connector/domain/ConnectorAction.java` 의 `decided(ConnectorConnection connection, AgentExecution origin, ...)` 는 `agentId = connection.agent().id()` 를 적는다
- 승인 실행: `connector/application/ConnectorActionService.java` 의 `beginApproval(user, actionId, grant)` 는 `connections.findByUserIdAndConnectorId` 로 연결을 찾고 `new Approval(..., connection.get().agent().hermesProfile())` 로 실행할 profile 을 정한다. `redecide(connection, manifest, action)` 이 있다
- 에이전트: `agent/application/AgentLifecycleService.java` 의 `changeVisibility`, `delete`, `requireManageable`, `requireGroupSafe`. `agent/application/AgentAdminService.java` 의 `update` 는 `agent.connectorManaged()` 면 `FORBIDDEN` 이다. `agent/application/AgentService.java` 의 `isEditableBy` 는 옛 커넥터 에이전트를 거짓으로 본다
- 도구 목록 읽기: `agent/application/AgentToolService.java` 의 `response(...)` 가 `AgentToolPolicy.isKnown` 이 아닌 켜진 이름을 `unclassified` 로 낸다. 화면은 그것을 경고로 보인다(`web/src/components/agent/agent-tools-section.tsx`)
- 실행 기록 가림: `hermes/HermesRunEventStream.java` 의 `connectorManaged` 인자와 `hermes/ToolDetailRedactor.java` 의 `redact(detail, connectorManaged, identifiers)`. `chat/application/ChatService.java` 가 `pending.agent().connectorManaged()` 를 넘긴다
- phase 02 가 만든 port: `agent/application/AgentConnectorBindings.java`(읽기: `hasBindings`, `connectorServers`, `connectorToolPrefixes`)와 `agent/application/AgentConnectorDetacher.java`(`detachAll`)
- 도구 쓰기: `agent/domain/AgentToolPolicy.java` 의 `requestedForWrite(user, agent, requested, current)` 는 모르는 이름을 거절하고 지금 켜진 목록에서 관리자 등급만 보존한 뒤 Control Plane MCP 를 더한다. `agent/application/AgentToolService.java` 와 `skill/infra/SkillPublisher.java` 가 부른다. 대시보드 plugin 은 바인딩 서버 이름이 빠진 목록을 409 로 거절한다(`docs/backend/connector-install.md` 의 「대시보드 plugin 계약」)
- 등록 이름: `connector/domain/HermesToolName.java` 의 `of(server, tool)`. 서버 접두사는 `HermesToolName.of(server, "")` 다

## 의도 메모

- 한 에이전트에 연결이 여럿이라 판정이 연결을 골라야 한다. 등록 이름의 서버 접두사로 고른다. 대시보드가 한 profile 에서 서버 이름 충돌을 막으므로 접두사가 맞는 바인딩은 하나다. 둘 이상 맞으면 줄 없이 막는다
- 판정의 연결 상태는 「연결 `READY` 이고 바인딩 `READY`」 일 때만 `READY` 로 넘긴다. 순수 함수의 모양은 바꾸지 않는다
- 승인한 호출은 그 줄의 에이전트 profile 에서 실행한다. 승인할 때 그 바인딩이 `READY` 여야 한다. 떼었으면 `not_executable` 이다
- 옛 커넥터 에이전트를 위한 경계 코드(`McpCallerResolver`, `AgentRunner` 와 `ChatService` 의 Memory 생략, `ExternalData` 감싸기, `AgentMemoryCollectionService`)는 지우지 않는다
- 도구 내용은 옛 커넥터 에이전트면 지금처럼 모두 가리고, 일반 에이전트면 붙은 커넥터 서버 접두사의 도구만 가린다. 외부 글이 실행 기록에 남지 않게 하는 것이 목적이다

## 작업 항목

### 1. 판정: `ConnectorPolicyService.decide`

- origin 을 찾은 뒤 `bindings.findByAgentId(origin.agentId())` 로 바인딩을 읽는다. 바인딩마다 연결의 manifest(`readManifest(connection.connectorId())`)로 `ownServerTool(manifest, hermesTool)` 가 참인 것을 고른다. 없으면 지금 「그 실행의 에이전트에 연결이 없다」 와 같게 줄 없이 막는다. 둘 이상이면 같은 방식으로 막는다
- 고른 바인딩의 `agent().hermesProfile()` 이 토큰의 profile 과 같아야 한다. 연결의 `userId` 가 origin 의 사용자와 같아야 한다
- 순수 함수에 넘기는 상태는 `connection.status() == READY && binding.status() == READY` 면 `READY`, 아니면 `PENDING` 이다
- `ConnectorAction.decided` 의 첫 인자를 연결 대신 `(ConnectorConnection connection, Long agentId)` 로 바꾸고 `agentId` 는 origin 의 에이전트다
- `stillReady(Long agentId)` 를 `stillReady(Long agentId, String connectorId)` 로 바꾼다. 그 에이전트에 그 커넥터의 연결이 붙어 있고 두 상태가 모두 `READY` 일 때만 참이다
- 주석 「커넥터 에이전트의 실행은 위임 자식이라」 는 직접 호출도 같은 트리 루트로 경계가 정해진다는 문장으로 고친다

### 2. 승인 실행: `ConnectorActionService`

- `beginApproval`: 연결을 찾은 뒤 `bindings.findByAgentIdAndConnectionId(action.agentId(), connection.id())` 가 있고 `READY` 여야 실행한다. 없거나 `READY` 가 아니면 `not_executable`. 실행할 profile 은 그 바인딩의 에이전트 profile 이다
- `redecide` 에 넘기는 상태도 1 과 같은 규칙이다
- phase 02 가 만든 `rejectPendingFor(connection, agentId, now)` 를 떼기에서 쓴다. 에이전트 단위 `EXECUTING` 검사도 같은 자리에 둔다

### 3. 에이전트의 공개와 삭제

- `AgentLifecycleService.changeVisibility` 와 `AgentAdminService.update`: 그룹으로 바꾸는데 `AgentConnectorBindings.hasBindings(agent.id())` 가 참이면 `AGENT_CONNECTIONS_REQUIRE_PRIVATE`(phase 02 가 더한 코드)
- `AgentLifecycleService.delete`: profile 이 Control Plane 이 만든 것이든 사람이 만든 것이든 먼저 `AgentConnectorDetacher.detachAll(agent)` 를 부른다. 사람이 만든 profile 은 거두지 않으므로 떼지 않으면 그 profile 에 커넥터 서버와 비밀이 남는다. 그 뒤 `profileManaged()` 면 profile 을 거둔다. 실패하면 지우지 않고 그 오류를 올린다
- 옛 커넥터 에이전트를 지울 수 있게 한다. `delete` 의 권한 판정은 주인과 `ADMIN` 이고 `isEditableBy` 의 옛 커넥터 예외를 이 경로에만 적용하지 않는다. 다른 편집 경로(성격, 스킬, 도구, 공개)는 지금처럼 막는다
- 사용자당 에이전트 상한 계산(`countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse`)은 그대로 둔다
- `AgentAdminService.update` 가 주인을 바꾸는데 `hasBindings` 가 참이면 `AGENT_HAS_CONNECTIONS`(새 코드, 409)로 거절한다. 남의 값이 든 profile 이 새 주인에게 넘어가고, 새 주인은 남의 연결이라 뗄 수도 없기 때문이다. 주인이 먼저 떼야 한다

### 4. 도구 목록 읽기와 쓰기

- `AgentToolService.response(...)` 의 `unclassified` 에서 `AgentConnectorBindings.connectorServers(agent.id())` 를 뺀다
- `AgentToolPolicy.requestedForWrite` 에 `Set<String> connectorServers` 인자를 더한다. 계산한 목록 끝에 그 이름을 더한다. 요청에 그 이름이 들어와도 모르는 이름으로 거절하지 않는다. 그 밖의 검사는 그대로다
- `AgentToolService` 의 쓰기와 `SkillPublisher` 가 `connectorServers(agent.id())` 를 넘긴다. 빠뜨리면 대시보드가 409 로 거절해 도구 저장과 스킬 게시가 실패한다

### 5. 실행 기록 가림

- `hermes` 패키지에 `record ToolDetailScope(boolean hideAll, Set<String> hiddenPrefixes)` 와 `boolean hides(String toolName)` 를 둔다
- `HermesRunEventStream` 과 `ToolDetailRedactor.redact` 의 `boolean connectorManaged` 를 `ToolDetailScope` 로 바꾼다. 가리는 글 `[연결 도구 내용 가림]` 과 스킬 기록 생략 규칙은 `hideAll` 일 때 지금과 같다. 일반 에이전트는 `hides(toolName)` 인 도구의 내용만 그 글로 바꾼다
- `ChatService` 는 turn 을 열 때 `agent.connectorManaged()` 면 `hideAll`, 아니면 `AgentConnectorBindings.connectorToolPrefixes(agent.id())` 로 범위를 만든다

### 6. 연결 엔티티에서 옛 칸 매핑 빼기

- `ConnectorConnection` 에서 `agent`, `restartRequired`, `desiredEnabled` 칸과 그것을 쓰는 메서드, 옛 팩터리 `pending(Long, String, Agent, Instant)` 를 지운다. `ConnectorConnectionRepository.findByAgentId` 를 지운다
- DB 의 칸은 지우지 않는다. `agent_id` 는 phase 01 에서 비워도 되게 했고 두 boolean 칸은 기본값이 있어 새 행이 저장된다. 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리하는 다음 작업이 둔다
- 옛 팩터리를 쓰던 시험(`ConnectorDeliveryRetryTest`, `ApprovalNotificationTest`, `ConnectorConnectionTest`)을 새 팩터리와 바인딩으로 고친다
- `docs/backend/schema/connector.md` 의 「connector_connection」 에 세 칸이 쓰지 않는 칸으로 남는다고 적고, 「connector_action」 의 `agent_id` 뜻을 「판정한 실행의 에이전트」 로 고친다

### 6-1. 판정의 안내 글

바인딩이 `PENDING` 이라 막은 호출은 지금 `NOT_READY_MESSAGE` 대신 「관리자가 반영을 마치면 이 연결을 쓸 수 있다」 는 글로 막는다. 연결 자체가 `READY` 가 아니면 지금 글 그대로다. 글은 `ConnectorPolicyService` 의 상수로 둔다.

### 7. 시험

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java`(수정): 일반 에이전트에 연결 둘을 붙이고 각 서버의 도구가 그 연결로 판정된다. 바인딩이 `PENDING` 이면 `NOT_READY`. 붙지 않은 커넥터의 도구는 줄 없이 막힌다. 남의 연결은 막힌다. 줄의 `agent_id` 가 실행의 에이전트다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java`(수정): 승인한 호출이 그 줄의 에이전트 profile 로 실행된다. 떼어진 바인딩의 승인은 `not_executable`
- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java`(수정): 바인딩이 있는 에이전트를 그룹으로 바꾸면 `AGENT_CONNECTIONS_REQUIRE_PRIVATE`. 지우면 `detachAll` 을 먼저 부른다. 옛 커넥터 에이전트도 주인이 지울 수 있다
- `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java`(수정): 붙은 커넥터 서버 이름이 `unclassified` 에 없다
- `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java`(수정): 일반 에이전트에서 붙은 커넥터 도구의 내용만 가린다. 옛 커넥터 에이전트는 모두 가린다
- `backend/src/test/java/com/bifos/assistant/agent/AgentAdminServiceTest.java`(수정): 바인딩이 있는 에이전트의 주인 변경은 `AGENT_HAS_CONNECTIONS`
- `backend/src/test/java/com/bifos/assistant/agent/AgentToolPolicyTest.java`(수정): `connectorServers` 가 목록 끝에 더해지고 요청에 들어와도 거절되지 않는다
- `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java`(수정): 스킬 게시가 붙은 커넥터 서버 이름을 함께 보낸다
- `backend/src/test/java/com/bifos/assistant/chat/ConnectorDeliveryRetryTest.java`, `ApprovalNotificationTest.java`, `ConnectorConnectionTest.java`(수정): 옛 팩터리 대신 새 팩터리와 바인딩

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorPolicyEndpointTest' --tests '*ConnectorActionServiceTest' --tests '*AgentLifecycleServiceTest' --tests '*AgentToolServiceTest' --tests '*ToolDetailEventStreamTest' --tests '*AgentAdminServiceTest' --tests '*AgentToolPolicyTest' --tests '*SkillServiceTest' --tests '*ConnectorDeliveryRetryTest' --tests '*ApprovalNotificationTest'
cd backend && ./gradlew test
scripts/quality.sh check
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorConnectionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentAdminService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/ToolDetailScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/ToolDetailRedactor.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyTestDoubles.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorDeliveryRetryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ApprovalNotificationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentAdminServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolPolicyTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillPublisher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` | 수정 |
| `docs/backend/schema/connector.md` | 수정 |
