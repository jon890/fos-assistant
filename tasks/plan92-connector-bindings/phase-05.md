# Phase 05. API 와 화면

**Execution profile**: deep

## 목표

사용자가 「연결」 화면에서 계정을 한 번 연결하고, 에이전트 상세의 「연결」 절에서 그 에이전트가 쓸 연결을 붙이고 뗀다.
관리자는 바인딩마다 반영 완료를 누른다. 옛 커넥터 에이전트는 「예전 방식의 연결 에이전트」 로 보이고 지울 수 있다.

**범위 외**: e2e 시나리오(phase 06). 책임 문서 전체의 갱신(phase 07).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/connectors.md` 의 「Control Plane API」, `docs/frontend/structure.md`

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`
- 지금 API: `docs/connectors.md` 의 「Control Plane API」. 컨트롤러는 `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorConnectionController.java`(`/api/v1/connectors`, `/api/v1/connections/{id}`, `/options/{fieldKey}`, `/check`), `ConnectorConnectionAdminController.java`(`GET /api/v1/admin/connections`, `POST /api/v1/admin/connections/{id}/{userId}/confirm`), 응답 모양은 `ConnectionDtos.java`
- phase 02 의 서비스: `ConnectorConnectionService`(연결), `ConnectorBindingService`(`listForAgent`, `bind`, `unbind`, `confirmApplied`)
- 웹
  - 연결 화면: `web/src/app/connections/page.tsx`, `web/src/app/connections/[id]/page.tsx`, `web/src/components/connector/connector-catalog.tsx`, `connector-connection-panel.tsx`(지금 「에이전트 열기」 링크가 `agentCode` 하나를 가리킨다), `connector-grants.tsx`, `connector-tools.tsx`
  - 관리자: `web/src/app/admin/connections/page.tsx`, `web/src/components/connector/connector-admin-panel.tsx`, 서버 경로 `web/src/app/api/admin/connections/[id]/[userId]/confirm/route.ts`
  - 에이전트 상세: `web/src/components/agent/agent-detail-loader.tsx`, `agent-detail-body.tsx`. 절은 성격, 도구, 스킬, 먼저 살펴보기, 모델(관리자), 관리(관리자), 공개와 삭제 순이다. `connectorManaged` 면 성격 대신 「연결 화면에서 이 에이전트의 연결 상태를 관리해요.」 를 보이고 도구, 스킬, 먼저 살펴보기 절을 숨긴다
  - 라이브러리: `web/src/lib/connection.ts`, `web/src/lib/connection-route.ts`, `web/src/lib/agent.ts`, `web/src/lib/agent-api.ts`
  - 서버 경로: `web/src/app/api/connections/[id]/...`, `web/src/app/api/agents/[code]/...`
  - 화면 문구 규칙: `web/AGENTS.md` 의 「화면 문구」
- Hermes 대역: `test/e2e/fake-hermes.ts`. 브라우저 검사와 e2e 가 함께 쓴다. 브라우저 시험의 준비 함수는 `test/browser/fixtures.ts` 의 `connectDemoConnector`, `disconnectDemoConnector`
- 브라우저 시험: `test/browser/connector-connection.spec.ts`, `test/browser/connector-agent-detail.spec.ts`, `test/browser/admin.spec.ts`

## 의도 메모

- 화면 말: 「붙이기」, 「떼기」, 「이 에이전트가 쓰는 연결」, 「반영 대기」, 「예전 방식의 연결 에이전트」. 「바인딩」 과 「커넥터 에이전트」 는 화면에 쓰지 않는다
- 붙이기 확인 창에 위험을 한 줄로 알린다: 「이 에이전트가 이 연결의 도구를 직접 써요. 터미널이나 파일 도구가 켜진 에이전트는 연결의 비밀값에 닿을 수 있어요.」 문구는 `web/AGENTS.md` 의 규칙에 맞춘다
- 관리자 반영 경로는 바인딩 단위라 주소가 바뀐다. 옛 주소는 웹의 서버 경로만 썼으므로 함께 바꾼다
- 「연결」 화면은 계정 하나를 다룬다. 어느 에이전트에 붙었는지는 목록으로만 보이고 붙이기는 에이전트 상세에서 한다

## 작업 항목

### 1. backend 경로

연결 경로의 응답 모양과 관리자 반영 완료 경로는 phase 02 가 이미 바꿨다. 이 phase 는 에이전트 경로를 더한다. 아래 표는 화면이 쓰는 경로 전체다.

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connectors` | 없음 | 지금 항목에 `bindings: [{agentCode, agentName, status, restartRequired}]` 를 더한다 |
| `GET /api/v1/connections/{id}` | 없음 | 상태 응답에서 `agentCode` 를 빼고 `bindings` 를 더한다. `restartRequired` 는 뺀다 |
| `POST /api/v1/connections/{id}` | `{values}` | 등록 또는 값 교체. 에이전트를 만들지 않는다 |
| `POST /api/v1/connections/{id}/check` | 없음 | 옛 값 옮기기, 확인 도구, 바인딩마다 다시 맞추기 |
| `DELETE /api/v1/connections/{id}` | 없음 | 바인딩을 모두 떼고 보관 파일을 지운다 |
| `GET /api/v1/agents/{code}/connections` | 없음 | `{connections: [AgentConnectionView], blockedReason}`. 주인만 |
| `PUT /api/v1/agents/{code}/connections/{connectorId}` | 없음 | 붙인다. `AgentConnectionView` |
| `DELETE /api/v1/agents/{code}/connections/{connectorId}` | 없음 | 뗀다. 204 |
| `GET /api/v1/admin/connections` | 없음 | 바인딩마다 한 항목. `connectorId, userId, displayName, agentCode, status, restartRequired, undeclaredTools` |
| `POST /api/v1/admin/agents/{code}/connections/{connectorId}/confirm` | 없음 | 반영 완료. 옛 `POST /api/v1/admin/connections/{id}/{userId}/confirm` 은 지운다 |

- 새 컨트롤러 `connector/presentation/AgentConnectionController.java` 가 에이전트 경로 셋을 갖는다. 관리자 반영 완료는 phase 02 의 `AdminAgentConnectionController` 다
- 응답에 env 이름, 보관 파일 이름, 서버 이름, 비밀 원문을 담지 않는다. `AgentConnectionView` 의 칸은 `connectorId, title, connectionStatus, bound, status, restartRequired, toolCount, skills` 다
- `AgentDtos` 의 에이전트 응답에 `connectorManaged` 는 그대로 두고 화면이 「예전 방식」 을 고르는 데 쓴다

### 2. Hermes 대역: `test/e2e/fake-hermes.ts`

`plan91-connector-binding-hermes` 가 연 경로를 흉내 낸다: 보관 파일 셋, `call` 의 `vault`, `PUT /api/connectors` 의 `bind`, `GET /api/connectors` 의 `mode`, 바인딩 설치 profile 의 `api_server` 보존. 바인딩 설치의 `restart_required` 는 참이다.
값은 메모리에만 두고 응답에 싣지 않는다.

### 3. 웹 라이브러리와 서버 경로

- `web/src/lib/connection.ts`: 상태 타입에서 `agentCode` 를 `bindings` 로 바꾼다
- `web/src/lib/agent-connection.ts`(신규): `AgentConnectionView` 타입과 `listAgentConnections`, `bindAgentConnection`, `unbindAgentConnection`
- 서버 경로 `web/src/app/api/agents/[code]/connections/route.ts`, `web/src/app/api/agents/[code]/connections/[connectorId]/route.ts`(신규), `web/src/app/api/admin/agents/[code]/connections/[connectorId]/confirm/route.ts`(신규). 옛 `web/src/app/api/admin/connections/[id]/[userId]/confirm/route.ts` 를 지운다. 본보기는 같은 디렉터리의 기존 경로다

### 4. 「연결」 화면

- `connector-connection-panel.tsx`: 「에이전트 열기」 링크 대신 「붙인 에이전트」 목록(에이전트 이름, 반영 대기 표시, 상세로 가는 링크)을 보인다. 없으면 「아직 이 연결을 쓰는 에이전트가 없어요. 에이전트 화면에서 붙여요.」
- 첫 설명을 「계정을 한 번 연결하고, 에이전트 화면에서 그 에이전트가 쓸 연결을 붙여요.」 로 바꾼다. 「전용 에이전트」 를 말하는 문구를 지운다
- `connector-catalog.tsx` 의 카드에 붙인 에이전트 수를 보인다

### 5. 에이전트 상세의 「연결」 절

- `web/src/components/agent/agent-connections-section.tsx`(신규). 도구 절 바로 뒤에 둔다. 주인에게만 보인다
- 줄마다 커넥터 이름, 도구 수, 상태(붙음, 반영 대기, 붙지 않음), 「붙이기」 또는 「떼기」 단추
- 붙일 수 없으면 까닭을 보인다: 그룹 공개 에이전트(「비공개 에이전트에만 붙일 수 있어요.」), 연결이 준비되지 않음(「연결 화면에서 연결을 확인해 주세요.」), 붙이기 오류 `CONNECTOR_PROFILE_NOT_READY`(「이 에이전트는 아직 연결을 받을 준비가 되지 않았어요. 관리자에게 알려 주세요.」), `CONNECTOR_BIND_CONFLICT`(「이 에이전트의 다른 연결이나 스킬과 이름이 겹쳐요.」)
- 연결이 하나도 없으면 「연결 화면」 으로 가는 링크만 보인다
- 붙이기 전에 확인 창을 거친다. 위 「의도 메모」 의 위험 문구를 보인다. 그 에이전트에 셸이나 파일 계열 도구가 켜져 있으면 「이 에이전트는 연결 도구의 승인 없이 그 서비스를 부를 수 있어요.」 를 더한다
- 커넥터에 스킬이 있고 그 에이전트의 `skills` 도구가 꺼져 있으면 「지침을 쓰려면 스킬 도구를 켜세요.」
- `agent-detail-loader.tsx` 가 주인일 때 `listAgentConnections` 를 함께 읽는다

### 6. 옛 커넥터 에이전트

- `agent-detail-body.tsx` 의 안내를 「예전 방식의 연결 에이전트예요. 쓰던 에이전트에 이 연결을 붙인 뒤 이 에이전트를 지워 주세요.」 로 바꾼다
- 그 에이전트의 공개와 삭제 절에서 삭제만 보인다. `agent-access-section.tsx` 가 `connectorManaged` 면 공개 범위 바꾸기를 숨기고 지우기를 보인다

### 6-1. 셸과 파일 도구의 위험 안내

`web/src/components/agent/agent-tools-section.tsx` 에서 관리자 등급 도구를 켤 때 거치는 확인 창에, 그 에이전트에 연결이 붙어 있으면 한 줄을 더한다: 「이 에이전트에 붙은 연결의 비밀값을 이 도구로 읽을 수 있고, 연결 도구의 승인 없이 그 서비스를 부를 수 있어요.」 붙은 연결이 있는지는 `listAgentConnections` 로 안다.

### 7. 관리자 화면

`connector-admin-panel.tsx` 가 바인딩마다 한 줄(사용자, 에이전트, 커넥터, 상태, 반영 대기)과 「반영 완료」 를 보인다. 반영 완료는 새 경로를 부른다.

### 8. 시험

- `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java`(신규): 주인이 붙이고 떼고, 남과 관리자는 `FORBIDDEN` 이나 `AGENT_NOT_FOUND`. 응답 본문에 env 이름, 보관 파일 이름, 서버 이름이 없다
- `test/browser/connector-connection.spec.ts`(수정): 연결한 뒤 붙인 에이전트가 없다는 안내가 보인다
- `test/browser/agent-connections.spec.ts`(신규): 비공개 에이전트에서 연결을 붙이면 확인 창의 위험 문구가 보이고 「반영 대기」 가 된다. 떼면 「붙지 않음」 이 된다. 그룹 공개 에이전트에서는 붙이기 단추 대신 까닭이 보인다
- `test/browser/connector-agent-detail.spec.ts`(수정): 옛 커넥터 에이전트는 「예전 방식」 안내와 지우기 단추를 보인다. 이 시험은 옛 에이전트를 시험 DB 에 직접 만드는 준비가 필요하면 `test/browser/fixtures.ts` 에 함수를 더한다
- `test/browser/admin.spec.ts`(수정): 관리자 연결 목록이 바인딩마다 한 줄이다
- `test/browser/fixtures.ts`(수정): `connectDemoConnector` 가 에이전트 번호 대신 연결 상태를 돌려주고, 붙이기 준비 함수 `bindDemoConnector(email, agentCode)` 를 더한다

## 검증

```bash
cd backend && ./gradlew test --tests '*AgentConnectionControllerTest'
cd backend && ./gradlew test
pnpm --dir web typecheck
pnpm --dir web test:browser connector-connection agent-connections connector-agent-detail admin
```

모두 종료 코드 0 이어야 한다. 브라우저 검사는 고친 화면의 spec 만 돌린다. 전체는 PR 의 CI 가 맡는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/AgentConnectionController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `web/src/lib/connection.ts` | 수정 |
| `web/src/lib/agent-connection.ts` | 신규 |
| `web/src/app/api/agents/[code]/connections/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/connections/[connectorId]/route.ts` | 신규 |
| `web/src/app/api/admin/agents/[code]/connections/[connectorId]/confirm/route.ts` | 신규 |
| `web/src/app/api/admin/connections/[id]/[userId]/confirm/route.ts` | 삭제 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `web/src/components/connector/connector-catalog.tsx` | 수정 |
| `web/src/components/connector/connector-admin-panel.tsx` | 수정 |
| `web/src/components/agent/agent-connections-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-loader.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-access-section.tsx` | 수정 |
| `web/src/components/agent/agent-tools-section.tsx` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
| `test/browser/agent-connections.spec.ts` | 신규 |
| `test/browser/connector-agent-detail.spec.ts` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
