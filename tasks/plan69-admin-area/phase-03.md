# Phase 03. 에이전트 관리와 커넥터 연결 확인을 관리자 영역으로 옮긴다

**Execution profile**: standard

## 목표

`/agents` 와 `/agents/{code}` 가 `ADMIN` 에게 더 그리던 관리 화면을 `/admin/agents` 와 `/admin/agents/{code}` 로 옮기고, `/connections` 아래의 관리자 패널을 `/admin/connections` 로 옮긴다.
일반 화면의 에이전트와 연결 화면은 역할과 상관없이 같은 모습이 된다.

**범위 외**: 사용량과 실행 상세(phase 04), 그룹 모델 설정(phase 05). backend.

## 컨텍스트

- `web/src/app/agents/page.tsx` 는 `ADMIN` 이면 `/api/v1/admin/agents` 를 읽어 `AgentAdminPanel`(`web/src/components/agent/agent-admin-panel.tsx`)을 그리고, 아니면 `/api/v1/agents` 로 목록을 그린다
- `web/src/app/agents/[code]/page.tsx` 는 `ADMIN` 이면 관리자 목록에서 그 에이전트를 찾아 `adminAgent` 로 넘기고, 목록에 없는 다른 사람의 비공개 에이전트는 도구를 관리자 경로로 읽는다(`useAdminTools`). `AgentDetailBody`(`web/src/components/agent/agent-detail-body.tsx`)가 `adminAgent` 가 있으면 「모델」 절(`AgentModelSection`)과 「관리」 절(`AgentAdminSection`)을 그린다
- `web/src/components/admin/agent-card.tsx` 의 「상세 보기」 는 `/agents/{code}` 로 간다
- `web/src/components/agent/agent-access-section.tsx` 는 지운 뒤 `router.push("/agents")` 한다
- `web/src/app/admin/agents/page.tsx` 는 지금 `/agents` 로 넘기는 옛 주소다
- `web/src/components/connector/connector-catalog.tsx` 가 `useShellIsAdmin()` 이 참이면 목록 아래에 `ConnectorAdminPanel` 을 그린다
- `web/src/app/agents/loading.tsx` 가 `useShellIsAdmin()` 으로 뼈대 모양을 고른다
- 서버에서 데이터를 읽는 화면 경로는 `loading.tsx` 를 함께 둔다(`docs/frontend/structure.md` 의 「디렉터리」 절)

**근거 문서**: `docs/frontend/structure.md` 의 「관리자 영역」 과 「에이전트 화면」 절

## 의도 메모

- 상세 화면의 데이터 읽기를 두 벌로 복사하지 않는다. 지금 `agents/[code]/page.tsx` 의 읽기를 `admin: boolean` 을 받는 함수 하나로 빼고 두 경로가 그것을 부른다
- 일반 화면의 상세는 `ADMIN` 이어도 관리자 목록을 읽지 않는다. `/api/v1/agents` 목록에 없는 에이전트는 `MEMBER` 역할 사용자와 같이 찾을 수 없다는 안내를 본다
- 일반 화면의 `ADMIN` 이 그룹 공개 에이전트를 고칠 수 있는 것은 그대로다. 목록의 `editable` 이 정한다
- `AgentAdminPanel` 안의 `CreateAgentDialog`(내 에이전트 만들기)는 일반 화면의 것이다. 관리자 영역의 목록에서는 뺀다

## 작업 항목

### 1. 일반 화면

- `web/src/app/agents/page.tsx`: 역할 분기를 지운다. 언제나 `/api/v1/agents` 목록과 `CreateAgentDialog` 를 그린다
- `web/src/app/agents/[code]/page.tsx`: 아래 2번의 함수를 `admin: false` 로 부른다
- `web/src/app/agents/loading.tsx`: 역할 분기를 지우고 일반 목록의 뼈대만 그린다
- `web/src/components/connector/connector-catalog.tsx`: `ConnectorAdminPanel` 과 `useShellIsAdmin` 을 지운다

### 2. `web/src/lib/agent-detail.tsx` (신규)

`loadAgentDetail(code: string, options: { admin: boolean })` 가 지금 `agents/[code]/page.tsx` 의 읽기와 판정을 갖고 `AgentDetailBody` 에 넘길 props 나 오류 화면을 돌려준다.
`admin` 이 거짓이면 `/api/v1/admin/agents` 를 읽지 않고 `adminAgent` 는 `undefined`, `useAdminTools` 는 거짓이다.
`admin` 이 참이면 지금 `ADMIN` 이 보던 것과 같다.

### 3. 관리자 영역

- `web/src/app/admin/agents/page.tsx`: 넘김을 지우고 `/api/v1/admin/agents` 를 읽어 `AgentAdminPanel` 을 그린다. 403 이면 `/` 로 넘긴다
- `web/src/app/admin/agents/loading.tsx` (신규): 지금 `agents/loading.tsx` 의 관리자 뼈대
- `web/src/app/admin/agents/[code]/page.tsx` (신규): `loadAgentDetail(code, { admin: true })`
- `web/src/app/admin/agents/[code]/loading.tsx` (신규): `web/src/app/agents/[code]/loading.tsx` 가 있으면 같은 뼈대를 쓴다
- `web/src/components/agent/agent-admin-panel.tsx`: `CreateAgentDialog` 를 빼고 제목을 「에이전트」 로 둔다
- `web/src/components/admin/agent-card.tsx`: 「상세 보기」 를 `/admin/agents/{code}` 로 바꾼다
- `web/src/components/agent/agent-access-section.tsx`: 지운 뒤 돌아갈 곳을 prop 으로 받는다. 관리자 영역에서는 `/admin/agents`, 일반 화면에서는 `/agents` 다. `AgentDetailBody` 가 넘긴다
- `web/src/app/admin/connections/page.tsx` (신규): 클라이언트 부품 하나가 `readConnectors()` 로 커넥터를 읽어 제목 「커넥터」 와 `ConnectorAdminPanel` 을 그린다. 확인할 연결이 없으면 「확인할 연결이 없어요.」 를 보인다

### 4. `test/unit/loading-routes.test.ts`

- `ROUTE_FRAMES` 에 `"admin/agents": "components/agent/agent-admin-panel.tsx"` 와 `"admin/agents/[code]": "components/agent/persona-editor.tsx"` 를 더한다
- 「에이전트 목록 뼈대는 레이아웃 역할로 …」 검사를 고친다. `agents/loading.tsx` 에는 역할 분기가 없고 `width="2xl"` 이며, `admin/agents/loading.tsx` 가 `width="4xl" title description="agent" form="agent"` 다. `useShellIsAdmin` 과 `AdminContext.Provider` 를 단언하는 줄은 지운다
- 「경로마다 loading.tsx 의 width …」 검사에서 `agents` 를 건너뛰는 줄을 지운다

### 5. 이 phase 를 검증하는 브라우저 검사

`test/browser/admin-area.spec.ts` 에 더한다.

- `ADMIN` 이 `/agents` 를 열면 운영 profile 등록 양식이 없고 「새 에이전트」 가 있다
- `ADMIN` 이 `/admin/agents` 를 열면 등록 양식과 목록이 있고, 「상세 보기」 가 `/admin/agents/{code}` 로 가며 거기에 「관리」 절과 `agent-model-section` 이 있다
- `ADMIN` 이 `/agents/{code}` 를 열면 「관리」 절과 `agent-model-section` 이 없다
- `/admin/connections` 가 열리고 `/connections` 에는 관리자 패널이 없다
- `MEMBER` 가 `/admin/agents` 를 열면 `/` 로 넘어간다

기존 검사가 관리 화면을 `/agents` 에서 찾는 자리를 `/admin/agents` 로 옮긴다. 아래 명령으로 찾는다.

```bash
grep -rln '"/agents\|/connections\|에이전트 관리\|사용 여부\|agent-model' test/browser/*.spec.ts
```

대상은 `test/browser/admin.spec.ts`, `agent-lifecycle.spec.ts`, `agent-model.spec.ts`, `agent-tools.spec.ts`, `connector-connection.spec.ts`, `connector-agent-detail.spec.ts`, `loading.spec.ts`, `missing-agent.spec.ts` 가운데 실제로 걸리는 것이다.

## 검증

```bash
# cwd: 저장소 root
(cd web && pnpm typecheck && pnpm lint)
node --test 'test/unit/**/*.test.ts'
(cd web && pnpm test:browser admin-area.spec.ts admin.spec.ts agent-lifecycle.spec.ts agent-model.spec.ts agent-tools.spec.ts connector-connection.spec.ts connector-agent-detail.spec.ts loading.spec.ts missing-agent.spec.ts persona.spec.ts skills.spec.ts)
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/app/agents/[code]/page.tsx` | 수정 |
| `web/src/app/agents/loading.tsx` | 수정 |
| `web/src/lib/agent-detail.tsx` | 신규 |
| `web/src/app/admin/agents/page.tsx` | 수정 |
| `web/src/app/admin/agents/loading.tsx` | 신규 |
| `web/src/app/admin/agents/[code]/page.tsx` | 신규 |
| `web/src/app/admin/agents/[code]/loading.tsx` | 신규 |
| `web/src/app/admin/connections/page.tsx` | 신규 |
| `web/src/app/admin/connections/connector-admin-view.tsx` | 신규 |
| `web/src/components/agent/agent-admin-panel.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-access-section.tsx` | 수정 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/components/connector/connector-catalog.tsx` | 수정 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `test/browser/admin-area.spec.ts` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
| `test/browser/agent-lifecycle.spec.ts` | 수정 |
| `test/browser/agent-model.spec.ts` | 수정 |
| `test/browser/agent-tools.spec.ts` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
| `test/browser/connector-agent-detail.spec.ts` | 수정 |
| `test/browser/loading.spec.ts` | 수정 |
