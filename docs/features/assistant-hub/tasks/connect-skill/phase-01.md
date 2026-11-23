# Phase 01. 연결은 전문 에이전트에만 붙인다

**Execution profile**: standard

## 목표

화면의 붙이기가 기본 비서를 거절하고, 에이전트 상세와 연결 뒤 고르기가 기본 비서에 붙이기를 보이지 않는다. `connector_attach` 승인 카드는 늘 새 전문 에이전트를 만들어 붙이고, 그 연결이 붙은 전문 에이전트가 있으면 카드 없이 그 에이전트를 알린다.

**범위 외**: 스킬 맡기기(phase 02, 03).

## 컨텍스트

**요구사항**: REQ9

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ9, DECISION7, DECISION25), `docs/features/assistant-hub/design.md` 의 「PR3」

- 붙이기: `connector/connection/presentation/AgentConnectionController`, `connector/connection/application/ConnectorBindingService`(`bind`, `listForAgent`, `blockedReason`), `connector/connection/application/dto/AgentConnectionsView`.
- 카드: `connector/action/application/ConnectorAttachRequester`, `ConnectorAttachRunner`, `connector/action/domain/ControlPlaneActions`, `mcp/application/McpConnectorAttachTool`(설명과 상태), `mcp/application/port/ConnectorAttachRequests`.
- 전문 에이전트 만들기: `connector/connection/application/SpecialistAgentCreator`. 웹의 기본값은 `web/src/lib/specialist-agent.ts` 의 `specialistDraft`.
- 웹: `web/src/lib/connector-onboarding.ts`(`agentChoices`), `web/src/components/agent/agent-connections-section.tsx`, `web/src/lib/agent-connection.ts`(까닭 타입), `web/src/components/error-message.ts`.

## 작업 항목

### 1. 화면의 붙이기

- `ErrorCode.PRIMARY_AGENT_NO_CONNECTIONS`(409)를 더한다.
- `AgentConnectionController` 의 붙이기가 기본 비서면 그 오류로 거절하고 아니면 `ConnectorBindingService.bind` 를 부른다. 목록은 기본 비서면 까닭을 `PRIMARY_AGENT` 로 바꾼다.

### 2. 승인 카드

- `ConnectorAttachRequester` 를 설계대로 바꾼다. 붙은 전문 에이전트가 있으면 `specialist_attached` 와 코드, 이름, 그 밖에는 새 에이전트 이름(`<서비스> 담당`)과 `agent_name` 인자로 카드. 키는 `<connector_id>:new`.
- `ConnectorAttachRunner` 가 `SpecialistAgentCreator.create` 로 만들고 붙인다. `agent_name` 이 없는 옛 카드는 붙이지 않는다.
- `ControlPlaneActions` 의 카드 제목, 인자 이름표, 필요한 인자를 고친다. `McpConnectorAttachTool` 설명과 `specialist_attached` 의 성공 처리를 고친다.

### 3. 웹

- `agentChoices` 가 `primary` 에이전트를 뺀다.
- 연결 절이 `PRIMARY_AGENT` 까닭이면 붙이기와 「외부 서비스 연결하기」 를 숨기고 떼기만 둔다. 안내 문구를 둔다.
- 오류 문구에 `PRIMARY_AGENT_NO_CONNECTIONS` 를 더한다.

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java`(통합): 기본 비서에 화면 붙이기 409, 목록의 까닭.
- `backend/src/test/java/com/bifos/assistant/connector/SpecialistAgentCreatorTest.java`(통합): 배포 전부터 비서에 붙은 연결을 비서에서 떼고 새 전문 에이전트에 옮긴다.
- `backend/src/test/java/com/bifos/assistant/mcp/McpConnectorAttachToolTest.java`(통합): 새 에이전트 카드 인자, 붙은 전문 에이전트가 있으면 카드 없음, 비서에만 붙었으면 `already_attached`, 승인하면 새 전문 에이전트가 생기고 붙음, 설치 실패면 거둠.
- `backend/src/test/java/com/bifos/assistant/connector/action/application/ConnectorAttachRunnerTest.java`(단위): 옛 카드 거절, 소개 기본값.
- `web/test/unit/connector-onboarding.test.ts`: `agentChoices` 가 기본 비서를 뺀다. 비서 상세에 붙이기가 없는 브라우저 검사는 phase 03 의 `skill-delegation.spec.ts` 에 둔다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorAttach*' --tests '*AgentConnection*' --tests '*SpecialistAgentCreatorTest' --tests '*ArchitectureRulesTest'
cd web && pnpm test:node && pnpm test:browser agent-connections connector-onboarding
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorAttachRequester.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorAttachRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/domain/ControlPlaneActions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/AgentConnectionsView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/AgentConnectionController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpConnectorAttachTool.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/port/ConnectorAttachRequests.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/SpecialistAgentCreatorTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/action/application/ConnectorAttachRunnerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpConnectorAttachToolTest.java` | 수정 |
| `web/src/components/agent/agent-connections-section.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/lib/agent-connection.ts` | 수정 |
| `web/src/lib/connector-onboarding.ts` | 수정 |
| `web/test/unit/connector-onboarding.test.ts` | 수정 |
