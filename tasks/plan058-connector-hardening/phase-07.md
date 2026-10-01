# Phase 07. 주인 화면이 커넥터 에이전트를 알아보고, 서비스 이름 검사가 hermes plugin 도 본다

**Execution profile**: standard

## 목표

작은 것 둘을 고친다. 사용자용 에이전트 응답에 `connectorManaged` 를 실어 `ADMIN` 이 아닌 주인의 화면도 커넥터 에이전트를 바르게 보이게 한다. 서비스 이름 검사의 범위에 `hermes/plugins` 를 더한다.

**범위 외**: 에이전트 종류를 enum 으로 나누는 것과 `agent_list` 응답은 바꾸지 않는다. `PENDING` 인 커넥터 에이전트는 꺼져 있어 목록에 없으므로 `MEMBER` 주인의 상세 화면이 여전히 일반 에이전트처럼 그려진다. 이 경우는 다루지 않는다. 편집 요청은 backend 가 거절한다.

## 컨텍스트

- 사용자용 응답은 `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` 의 `AgentView(code, name, visibility, acceptsAttachments, editable, ownedByMe)` 이고 `AgentView.from(Agent, boolean, boolean)` 이 만든다. 관리자용 `AdminAgentView` 는 이미 `connectorManaged` 를 갖는다
- `web/src/app/agents/[code]/page.tsx` 는 `adminAgent?.connectorManaged === true` 로만 판정한다. `MEMBER` 역할의 주인은 `adminAgent` 가 없어 커넥터 에이전트의 도구와 스킬 편집 영역이 일반 에이전트처럼 그려진다. 그 요청은 backend 가 거절한다
- `web/src/lib/agent.ts` 의 `AgentView` 타입이 응답 모양을 갖는다
- `test/unit/connector-neutral.test.ts` 는 `git ls-files backend/src/main web/src` 의 파일만 본다. `hermes/plugins` 에 금지 낱말이 든 파일은 지금 없다

**근거 문서**: `docs/connectors.md` 의 「설치와 실패 처리」, `docs/code-architecture.md` 의 「backend 패키지」

## 작업 항목

### 1. `AgentView` 에 `connectorManaged`

- `AgentDtos.AgentView` 의 마지막 칸으로 `boolean connectorManaged` 를 더하고 `from` 이 `agent.connectorManaged()` 를 넣는다. Javadoc 에 `@param connectorManaged` 한 줄을 더한다
- `AgentView` 를 직접 만드는 다른 자리가 있는지 `git grep -n "new AgentView("` 로 확인하고 맞춘다

### 2. 웹

- `web/src/lib/agent.ts` 의 `AgentView` 에 `connectorManaged: boolean` 과 한 줄 주석을 더한다
- `web/src/app/agents/[code]/page.tsx`: `const connectorManaged = listed?.connectorManaged === true || adminAgent?.connectorManaged === true;`
- `AgentView` 응답을 검사하거나 손으로 만드는 웹 코드와 대역을 `git grep -n "ownedByMe" web/src test` 로 찾아 새 칸을 맞춘다. 대역이 그 칸을 빼먹어도 화면은 거짓으로 읽어야 한다

### 3. 서비스 이름 검사

- `test/unit/connector-neutral.test.ts` 의 `git ls-files` 인자에 `hermes/plugins` 를 더하고 파일 머리 주석의 범위를 고친다
- 금지 낱말이 든 `hermes/plugins` 파일의 이름을 돌려주는 검사 한 줄을 「금지 낱말이 든 파일은 그 이름을 돌려준다」 의 입력에 더한다(`"hermes/plugins/x/__init__.py"`)

### 4. 이 phase 를 검증하는 테스트

- backend: `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` 에 단언을 더한다. 에이전트 목록(`/api/v1/agents`)은 켜진 에이전트만 낸다. 켜진 커넥터 에이전트(`markConnectorManaged()` 뒤 enabled 인 것)는 `connectorManaged` 가 참이고 일반 에이전트는 거짓임을 본다
- web: 새 파일 `test/browser/connector-agent-detail.spec.ts`. 에이전트 상세 화면은 서버 컴포넌트가 Control Plane 을 직접 불러 `page.route` 로 바꿔 끼울 수 없다. 실제 backend 에 연결을 만든다
  - `test/browser/fixtures.ts` 에 보조 함수 `connectDemoConnector(email)` 과 `disconnectDemoConnector(email)` 을 더한다. 같은 파일의 `setAgentVisibility` 가 JWT 를 서명해 `fetch` 하는 방식을 그대로 쓰되 subject 를 인자의 사용자로 한다. `connectDemoConnector` 는 `POST /api/v1/connections/<시험 커넥터 id>` 에 `{ values: { token: "demo_ok_0123456789" } }` 를 보내고 이어서 `POST /api/v1/connections/<id>/check` 를 보내 `READY` 임을 확인한 뒤 응답의 `agentCode` 를 돌려준다. 시험 커넥터 id 와 통과하는 토큰 값은 `test/e2e/fake-hermes.ts` 의 `DEMO_CONNECTOR` 와 `test/e2e/scenarios/connector.ts` 의 `DEMO_TOKEN_OK` 에서 읽어 맞춘다. 대역이 요구하는 칸이 더 있으면 그 값도 보낸다
  - `MEMBER` 사용자는 `member@example.com` 이다. `test/browser/admin.spec.ts` 와 `test/browser/persona.spec.ts` 가 그 사용자로 로그인하는 방식을 쓴다
  - 검사: `connectDemoConnector("member@example.com")` 뒤 그 사용자로 `/agents/<agentCode>` 를 연다. 도구 편집 영역과 스킬 영역이 없고 커넥터 에이전트 안내가 보임을 본다. testid 는 `web/src/components/agent/agent-detail-body.tsx` 에서 읽는다
  - 같은 backend 를 다른 spec 이 쓴다. `test.afterEach` 에서 `disconnectDemoConnector` 로 연결을 해제한다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*AgentControllerLifecycleTest'
```

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser connector-agent-detail
```

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
grep -n "hermes/plugins" test/unit/connector-neutral.test.ts
scripts/check-public-safe.sh
```

브라우저 검사 전에 다른 브라우저 검사가 돌고 있지 않은지 team-lead 에게 확인한다. 모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/app/agents/[code]/page.tsx` | 수정 |
| `test/unit/connector-neutral.test.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` | 수정 |
| `test/browser/connector-agent-detail.spec.ts` | 신규 |
| `test/browser/fixtures.ts` | 수정 |
