# Phase 01. 에이전트 목록을 하나로 합친다

**Execution profile**: standard

## 목표

`/agents` 목록 하나가 모두의 목록과 `ADMIN` 의 관리 목록을 함께 맡고, 메뉴 「에이전트 관리」 를 없앤다.
같은 에이전트의 설정이 두 메뉴에 흩어져 있어 사용자가 어디서 무엇을 고치는지 헷갈렸다.

**범위 외**: 상세 화면의 관리 절은 phase 02 가 옮긴다. 이 phase 에서는 목록 카드의 관리 단추(공개 범위, 사용 여부, 주소, 모델)를 아직 옮기지 않고, `ADMIN` 목록의 카드는 상세로 가는 링크만 둔다. backend 와 `/api/v1/**` 경로는 바꾸지 않는다. 사람 관리(`/admin/people`)는 그대로다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「web 화면 구조」 표와 「에이전트 화면」 절

지금 모양(구현 전에 다시 읽는다):

| 자리 | 지금 |
| --- | --- |
| `web/src/components/shell/main-nav.tsx` | `LINKS` 에 `/agents` 「에이전트」, `isAdmin` 이면 `/admin/agents` 「에이전트 관리」 와 `/admin/people` 「사람 관리」 를 더한다 |
| `web/src/app/agents/page.tsx` | 서버 컴포넌트. `callControlPlane<AgentView[]>("/api/v1/agents")` 로 목록을 그린다 |
| `web/src/app/admin/agents/page.tsx` | `callControlPlane<AdminAgent[]>("/api/v1/admin/agents")`, 403 이면 `/` 로 넘긴다. `AgentAdminPanel` 을 그린다 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 막힌 provider 알림(`/api/admin/providers/blocked`, `data-testid="blocked-providers"`), `AgentForm` 등록, `AgentList`(카드 동작), `VisibilityConfirm` |
| `web/src/app/layout.tsx` | `me?.role === "ADMIN"` 으로 `isAdmin` 을 정한다 |
| 타입 | `web/src/lib/agent.ts` 의 `AgentView`(쓸 수 있는 것), `AdminAgent`(`ownerUserId`, `enabled`, `visibility` 를 가진 관리용) |

## 의도 메모

- `ADMIN` 목록에는 다른 사람의 비공개 에이전트와 꺼 둔 에이전트도 보인다(사용자 결정). 「다른 사람 것」 은 `visibility === "PRIVATE"` 이고 `ownerUserId` 가 내 id 와 다를 때, 「꺼짐」 은 `enabled === false` 일 때 붙인다. 내 id 는 레이아웃이 이미 읽는 `me` 를 쓴다
- `ADMIN` 이 아니면 지금 `/agents` 와 똑같이 보인다
- 옛 주소 `/admin/agents` 는 `/agents` 로 넘긴다. 북마크가 깨지지 않게 한다
- 새로 만드는 한국어 문구는 해요체로 쓴다(화면 문구를 해요체로 맞추는 작업이 따로 진행 중이다)

## 작업 항목

### 1. `/agents` 가 `ADMIN` 에게 관리 목록을 보인다

`web/src/app/agents/page.tsx` 에서 역할을 읽고, `ADMIN` 이면 `/api/v1/admin/agents` 목록으로 그린다. 목록 위에 막힌 provider 알림과 `AgentForm`(새 에이전트 등록)을 둔다. 등록 뒤 목록을 다시 읽는다. `agent-admin-panel.tsx` 의 등록과 알림 부분을 옮겨 쓰고, 옮긴 뒤 쓰이지 않는 코드는 지운다. 표시 배지는 `web/src/components/ui/badge` 를 쓴다

### 2. 메뉴와 옛 주소

- `main-nav.tsx` 에서 「에이전트 관리」 링크를 뺀다
- `web/src/app/admin/agents/page.tsx` 는 `redirect("/agents")` 만 한다. `loading.tsx` 는 지운다

### 3. 이 phase 를 검증하는 브라우저 검사

`test/browser/admin.spec.ts` 의 목록과 등록 검사를 `/agents` 로 옮긴다.
- 정상: `ADMIN` 세션에서 `/agents` 에 새 에이전트 등록이 보이고, 등록하면 목록에 나타난다. 다른 사람의 비공개 에이전트에 「다른 사람 것」 표시가 있다
- 실패 쪽: `MEMBER` 세션에서 `/agents` 에 등록 폼과 다른 사람의 비공개 에이전트가 보이지 않고, 메뉴에 「에이전트 관리」 가 없다. `/admin/agents` 로 가면 `/agents` 에 닿는다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
grep -rn '에이전트 관리' web/src
```

- 앞의 둘이 통과한다
- 마지막 grep 이 아무것도 가리키지 않는다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/app/admin/agents/page.tsx` | 수정 |
| `web/src/app/admin/agents/loading.tsx` | 삭제 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
