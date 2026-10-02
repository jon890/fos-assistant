# Phase 02. 관리자 영역의 틀과 입구

**Execution profile**: deep

## 목표

`/admin` 아래가 사이드바 없이 자기 틀을 쓰게 하고, 일반 화면의 사이드바 맨 아래 줄에 `ADMIN` 만 보는 입구 「관리자」 하나를 둔다.
대화 목록이 길거나 화면 높이가 낮아도 입구가 보여야 하고, 역할을 읽지 못했을 때 입구가 말없이 사라지지 않아야 한다.

**범위 외**: 관리 화면의 내용을 옮기는 것(phase 03, 04, 05). 이 phase 가 끝난 뒤에도 일반 화면의 관리자 표시는 그대로 보인다. `useShellIsAdmin()` 의 뜻은 phase 04 가 바꾼다.

## 컨텍스트

- 화면 틀은 `web/src/components/shell/app-shell.tsx` 의 `AppShell` 과 `ShellBody` 다. 루트 레이아웃 `web/src/app/layout.tsx` 가 `readMe()` 로 읽은 역할과 이름을 넘긴다. `readMe()` 는 읽지 못하면 `null` 을 준다(`web/src/lib/me.ts`)
- 사이드바는 `web/src/components/shell/sidebar.tsx`, 메뉴는 `web/src/components/shell/main-nav.tsx` 다. 지금 메뉴 끝에 `ADMIN` 에게만 「사용자 관리」 링크가 붙는다
- 지금 사이드바의 아래 덩어리(메뉴와 이름 줄)는 줄어들지 않는다. 화면 높이가 낮으면 아래쪽이 잘린다
- 브라우저가 역할을 다시 읽을 길은 `GET /api/me` 다(`web/src/app/api/me/route.ts`)
- 브라우저 검사의 기본 세션은 `ADMIN` 이다. `MEMBER` 는 `setSession(context, { email: "member@example.com", name: "가족 사용자" })` 로 바꾼다(`test/browser/fixtures.ts`, 선례 `test/browser/people.spec.ts`)
- 대화가 많은 사이드바를 만드는 선례는 `test/browser/conversation-requests.spec.ts` 에 있다

**근거 문서**: `docs/frontend/shell.md` 의 「화면 틀」, 「관리자 입구」, 「관리자 영역의 틀」 절. `docs/frontend/structure.md` 의 「관리자 영역」 절

## 의도 메모

- 관리자 영역의 틀은 새 색을 쓰지 않는다. 머리의 「관리자」 는 `Badge` 다
- 머리에 그룹이나 가족을 뜻하는 이름을 적지 않는다
- 입구는 하나다. 접힌 사이드바의 위 막대나 좁은 화면의 위 막대에 입구를 더 두지 않는다
- 관리자 영역에서는 대화 목록을 읽지 않는다. `ConversationsProvider` 의 `enabled` 를 끈다

## 작업 항목

### 1. `web/src/components/shell/app-shell.tsx`

- `AppShell` 의 props 를 `role: "ADMIN" | "MEMBER" | null` 과 `displayName` 으로 바꾼다. `role` 이 `null` 이면 역할을 읽지 못한 것이다
- `role` 이 `null` 이고 로그인한 화면이면 올라온 뒤 `fetchMe()`(아래 5번)를 한 번 불러 역할과 이름을 채운다. 그래도 못 읽으면 실패 상태로 둔다. 「다시 읽기」 가 같은 조회를 다시 한다
- 사이드바에는 context 로 넘긴다. `ShellAccountContext` 에 `{ state: ShellRoleState; displayName: string | null; retry(): void }` 를 싣고 `useShellAccount()` 로 읽는다. 붙박이 사이드바와 서랍의 사이드바가 같은 값을 읽는다. `Sidebar` 의 `isAdmin`, `displayName` prop 은 지운다
- `ShellRoleState` 와 판정은 순수 함수 파일 `web/src/components/shell/role-state.ts` 에 둔다. `shellRoleState(role: "ADMIN" | "MEMBER" | null, retried: boolean): "admin" | "member" | "reading" | "failed"`. `role` 이 `null` 이고 아직 다시 읽지 않았으면 `reading`, 다시 읽고도 `null` 이면 `failed` 다. `null` 을 `member` 로 치지 않는다. 이 파일은 다른 web 파일을 import 하지 않는다
- `inAdminArea` 는 경로가 `/admin` 이거나 `/admin/` 로 시작할 때 참이다. 참이면 사이드바와 좁은 화면의 위 막대 대신 `AdminShell` 로 `children` 을 감싼다
- `useAdminView()` 를 내보낸다. `role === "ADMIN" && inAdminArea` 다. `useShellIsAdmin()` 은 이 phase 에서 지우지 않고 `role === "ADMIN"` 을 그대로 돌려준다
- 경로가 `/chat/{id}` 일 때마다 그 경로를 `sessionStorage` 의 `last-conversation-path` 에 적는다. 저장소를 못 쓰면 넘어간다

### 2. `web/src/components/shell/admin-shell.tsx` (신규)

- 머리: `Badge` 「관리자」 와 링크 「사용 화면으로 돌아가기」. 링크의 목적지는 `sessionStorage` 의 `last-conversation-path` 이고 없거나 `/chat/` 로 시작하지 않으면 `/` 다
- 메뉴 `nav`(`aria-label="관리자 메뉴"`): 「사용자」 `/admin/people`, 「에이전트」 `/admin/agents`, 「모델」 `/admin/models`, 「사용량과 비용」 `/admin/usage`, 「커넥터」 `/admin/connections`. 지금 경로의 링크에 `aria-current="page"`. `/admin/executions/` 아래는 「사용량과 비용」 을 고른 것으로 친다
- `md` 이상은 왼쪽 세로 메뉴, `md` 미만은 머리 아래 가로로 밀리는 한 줄(`overflow-x-auto`)이다
- 본문은 `main` 이고 그 안에서만 스크롤한다. 화면 전환 움직임은 일반 화면과 같이 `ScreenTransition` 을 쓴다

### 3. `web/src/components/shell/sidebar.tsx`, `main-nav.tsx`

- `MainNav` 에서 `isAdmin` 과 「사용자 관리」 링크를 지운다
- 사이드바를 세 구역으로 나눈다. 대화 목록은 지금처럼 남는 높이를 쓰고 먼저 줄어든다. 메뉴 구역은 `min-h-0 shrink overflow-y-auto` 로 그다음에 줄어든다. 맨 아래 줄은 `shrink-0` 이다
- 맨 아래 줄: 이름, `ADMIN` 이면 링크 「관리자」(`href="/admin"`, `data-testid="admin-entry"`), 밝기 단추. 역할을 읽지 못했으면(`meFailed`) 이름 자리에 「계정 정보를 읽지 못했어요」 와 단추 「다시 읽기」 를 보인다

### 4. `web/src/app/layout.tsx`, `web/src/app/admin/layout.tsx`(신규), `web/src/app/admin/page.tsx`(신규)

- 루트 레이아웃은 `role={me?.role ?? null}` 을 넘긴다
- `admin/layout.tsx` 는 서버에서 세션과 `readMe()` 를 본다. 세션이 없으면 `/signin`, 역할이 `MEMBER` 면 `/` 로 넘긴다. 역할을 읽지 못했으면 넘기지 않고 `Notice` 로 「계정 정보를 읽지 못했어요. 잠시 뒤 다시 열어 주세요.」 를 보인다
- `admin/page.tsx` 는 `/admin/people` 로 넘긴다
- 메뉴가 가리키는 `/admin/models`, `/admin/usage`, `/admin/connections` 는 뒤 phase 가 만든다. 이 phase 에서는 `/admin/agents` 의 기존 넘김(`/agents`)을 그대로 둔다

### 5. `web/src/lib/me-client.ts` (신규)

화면 부품은 `fetch` 를 직접 부르지 못한다(`web/eslint.config.mjs` 의 `no-restricted-globals`). `web/src/lib/me.ts` 는 서버 전용이다.
브라우저용 `fetchMe(): Promise<{ role: "ADMIN" | "MEMBER"; displayName: string } | null>` 를 둔다. `GET /api/me` 를 `cache: "no-store"` 로 부르고, 응답이 성공이 아니거나 예외가 나면 `null` 을 준다.

### 6. 이 phase 를 검증하는 `test/browser/admin-area.spec.ts` (신규)

모바일과 데스크톱 두 폭에서 돈다. 모바일은 서랍을 열어 본다.

- 대화가 화면 높이보다 많을 때 `admin-entry` 가 뷰포트 안에 있다(`toBeInViewport`)
- 뷰포트 높이를 360px 로 줄여도 `admin-entry` 가 뷰포트 안에 있다
- `MEMBER` 세션에는 `admin-entry` 가 없고, `/admin/people` 을 열면 `/` 로 넘어간다
- 입구를 누르면 `/admin/people` 이 열리고 사이드바(`aria-label="사이드바"`)가 없고 「관리자 메뉴」 가 있다
- 대화 하나를 연 뒤 입구로 들어갔다가 「사용 화면으로 돌아가기」 를 누르면 그 대화의 주소로 돌아온다. 대화를 연 적이 없으면 `/` 다
- 역할을 읽지 못한 분기는 브라우저 검사로 만들 수 없다. 레이아웃의 `readMe()` 가 서버에서 돌아 `page.route` 로 막지 못한다. 단위 검사 `test/unit/role-state.test.ts`(신규)로 본다. `shellRoleState(null, false)` 는 `reading`, `shellRoleState(null, true)` 는 `failed`, `shellRoleState("MEMBER", false)` 는 `member`, `shellRoleState("ADMIN", true)` 는 `admin` 이다. `null` 이 `member` 가 되지 않는다는 것이 이 검사의 뜻이다

`test/unit/loading-routes.test.ts` 의 「에이전트 목록 뼈대는 …」 검사 마지막 줄은 `app-shell.tsx` 에 `AdminContext.Provider value={isAdmin}` 이 있다고 단언한다. `AppShell` 의 props 가 `role` 로 바뀌므로 그 줄을 새 표현에 맞게 고친다. 이 phase 에서 `useShellIsAdmin()` 은 여전히 역할만 본다

기존 검사를 고친다.

- `test/browser/people.spec.ts`: 「사용자 관리」 링크가 없다는 단언을 `admin-entry` 가 없다는 단언으로 바꾼다
- `test/browser/nav.spec.ts`: 역할별 메뉴 검사에 `ADMIN` 에게 `admin-entry` 가 보인다는 단언을 더한다
- `test/browser/shell.spec.ts`: 사이드바 구조를 단언하는 자리가 새 구역과 맞는지 본다

## 검증

```bash
# cwd: 저장소 root
(cd web && pnpm typecheck && pnpm lint)
! grep -rn 'style={{' web/src/components/shell/
(cd web && pnpm test:browser admin-area.spec.ts people.spec.ts nav.spec.ts shell.spec.ts)
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/components/shell/admin-shell.tsx` | 신규 |
| `web/src/components/shell/role-state.ts` | 신규 |
| `web/src/lib/me-client.ts` | 신규 |
| `test/unit/role-state.test.ts` | 신규 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/app/admin/layout.tsx` | 신규 |
| `web/src/app/admin/page.tsx` | 신규 |
| `test/browser/admin-area.spec.ts` | 신규 |
| `test/browser/people.spec.ts` | 수정 |
| `test/browser/nav.spec.ts` | 수정 |
| `test/browser/shell.spec.ts` | 수정 |
