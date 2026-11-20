# Phase 01. 사이드바를 줄이고 이름 메뉴와 로그아웃을 만든다

**Execution profile**: standard

## 목표

사이드바를 대화 중심의 줄만 남기고, 나머지 입구를 이름 메뉴로 옮기고, 로그아웃을 새로 만든다.

**범위 외**: 「설정」 화면과 설정 틀(phase-02). 이 phase 에서 「설정」 줄은 `/settings` 로 가는 링크만 두고 그 화면은 phase-02 가 만든다.

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ6, REQ7

**근거 문서**: `docs/features/app-shell/spec.md`, `docs/features/app-shell/design.md`

- 사이드바는 `web/src/components/shell/sidebar.tsx`, 메뉴는 `main-nav.tsx`. 붙박이 사이드바와 휴대폰 서랍이 같은 `Sidebar` 를 그린다(`app-shell.tsx`).
- 역할 상태와 이름은 `useShellAccount()`(`shell-account.ts`, `role-state.ts`).
- 밝기는 `next-themes` 의 `useTheme`. 지금 단추는 `web/src/components/ui/theme-toggle.tsx` 이고 사이드바만 쓴다.
- 메뉴 부품은 `web/src/components/ui/dropdown-menu.tsx`(Radix). 하위 메뉴와 라디오 묶음이 있다.
- 로그인은 Auth.js(`web/src/auth.ts`). 세션 확인은 page 마다 `auth()` 다. `lib/control-plane.ts` 의 `endRevokedSession` 이 `signOut({ redirect: false, ... })` 을 쓰는 예다.
- 화면 문구 규칙은 `docs/code-rules/rule-58.md`, 브라우저 테스트 규칙은 `rule-60`, `rule-61`, `rule-62`, `web/test/browser/README.md`.

## 의도 메모

- 로그아웃을 Auth.js 의 `/api/auth/signout` 양식이나 서버 동작으로 보내지 않는다. 앞은 CSRF 토큰을 따로 읽어야 하고, 뒤는 쿠키를 바꾼 응답에서 지금 경로를 다시 그리고 배포 뒤 열린 탭에서 실패한다(설계 DECISION4).
- 로그아웃 뒤 `router.push` 를 쓰지 않는다. 클라이언트 라우터 캐시가 로그아웃 전 화면을 남긴다.
- 「화면 밝기」 는 하위 메뉴로 만들지 않는다(설계 DECISION6).
- 계정 고르기는 모든 로그인에 붙인다(설계 DECISION3).

## 작업 항목

### 1. `web/src/components/shell/main-nav.tsx`

`LINKS` 를 「예약 작업」(`/tasks`), 「기억」(`/memory`) 두 줄로 줄이고 `ADVANCED_LINKS` 와 「고급」 묶음을 지운다. 기억의 제안 수 표시는 그대로 둔다. `nav` 의 낭독기 이름 「주요 화면」 은 그대로 둔다.

### 2. `web/src/components/shell/sidebar.tsx`

차례를 앱 이름 줄(왼쪽 묶음에 앱 이름과 `NotificationBell`, 오른쪽에 넓은 화면의 접기 단추) → `RoomLink` → 「새 대화」 → 대화 검색 → `NowLink` → `MainNav` → `ConversationNav` → `AccountMenu` 로 바꾼다. 서랍에서는 앱 이름 줄 오른쪽에 서랍 닫기 단추(`components/ui/sheet.tsx` 의 `absolute top-3 right-3`) 폭만큼 여백을 둔다. 높이가 모자라면 `ConversationNav` 가 먼저 0 까지 줄고(`basis-0 grow min-h-0`), 그다음 `MainNav` 구역이 줄며 그 안에서 스크롤한다(`min-h-0 shrink overflow-y-auto`). 나머지는 `shrink-0` 이다. 맨 아래 줄의 이름, 관리자 링크, 알림 단추, `ThemeToggle` 을 지운다.

### 3. `web/src/components/shell/account-menu.tsx`(신규)

`AccountMenu({ onNavigate })`. 설계의 「이름 메뉴」 표대로 그린다.

- 트리거는 사람 아이콘과 이름(모르면 「계정」)이고 `aria-label="계정 메뉴"`. 역할 상태가 `failed` 면 줄에 안내 글자 「계정 정보를 읽지 못했어요」, 「다시 읽기」(`account.retry`), 아이콘만 보이는 트리거를 형제로 둔다.
- 「관리자 화면」 줄은 역할 상태가 `admin` 일 때만 그리고 `data-testid="admin-entry"` 를 붙인다.
- 「화면 밝기」 는 `DropdownMenuLabel` 아래 `DropdownMenuRadioGroup`. 값과 이름은 `light: 밝게`, `dark: 어둡게`, `system: 기기 설정`. 마운트 전에는 라디오를 고르지 않은 채 둔다.
- 「로그아웃」 은 `fetch("/signout", { method: "POST" })`. 204 면 `window.location.replace("/signin")`. 다른 응답이거나 reject 면 메뉴 밖, 이름 줄 위에 `role="alert"` 로 「로그아웃하지 못했어요. 아직 로그인된 상태예요」 를 보인다. 요청이 도는 동안 다시 보내지 않는다.

### 4. `web/src/app/signout/route.ts`(신규)

`POST` 만 둔다. `signOut({ redirect: false })` 를 부르고 204, 던지면 `console.error` 로 남기고 500. `app/signout/revoked/route.ts` 는 그대로다.

### 5. `web/src/auth.ts` 와 `web/src/components/shell/app-shell.tsx`

- `Google({ ..., authorization: { params: { prompt: "select_account" } } })`. 이유를 주석 한 줄로 남긴다.
- `AppShell` 에 `pageshow` 처리를 둔다. `event.persisted` 가 참이면 `location.reload()`. 로그인 화면에서는 두지 않는다.

### 6. `web/src/components/ui/theme-toggle.tsx`

지운다. `test/browser/theme.spec.ts` 를 이름 메뉴로 고친다.

### 7. 브라우저 테스트

- 공통: 360px 사례는 `page.setViewportSize({ width: 360, height: 780 })` 로 맞춘다(`mobile` 프로젝트는 390px). 사이드바를 보는 테스트는 사이드바 틀 경로(`/new`)에서 출발한다. phase-01 동안 `/settings` 는 아직 없으므로 사라진 링크로 가던 테스트는 주소로 바로 연다.
- `web/test/browser/shell.spec.ts`: 사이드바 줄의 차례와 없는 줄(「에이전트」, 「사용량」, 「고급」), 「예약 작업」 → `/tasks` 와 「기억」 → `/memory`, 탭 막대가 없음, 대화가 많을 때 「기억」 줄도 보이는지, 알림 단추의 읽지 않은 수와 맨 아래 줄에 알림 단추가 없음, 휴대폰 위 막대의 알림 단추가 그대로인지, 앱 이름 옆 알림 단추가 서랍 닫기 단추와 겹치지 않는지, 대화를 많이 만든 뒤와 낮은 높이(640×320)에서 계정 메뉴 단추가 스크롤 없이 보이는지. 넓은 화면과 `mobile` 프로젝트(서랍) 모두.
- `web/test/browser/account-menu.spec.ts`(신규): 메뉴 맨 위 이름, 그 아래 「설정」 → 「관리자 화면」 → 「화면 밝기」 → 「로그아웃」 차례(`MEMBER` 역할은 「관리자 화면」 없음), 지금 밝기의 라디오가 체크됨, 키보드로 열고 화살표로 「어둡게」 를 골라 `Enter`, 「관리자 화면」 이 `/admin` 으로 가는지, 밝기 어둡게(360px 에서는 탭) → 새로 고침 뒤 `html.dark` 유지, 키보드(`Enter` 로 열고 `Esc` 로 닫기), 360px 사례는 `test.use({ hasTouch: true })` 와 `tap()`. 역할 읽기 실패는 서버의 첫 읽기(`readMe`)를 브라우저 테스트에서 실패시킬 수단이 없어 테스트로 만들지 않는다. 상태 판정 `shellRoleState` 를 바꾸지 않고, 실패 상태의 배치는 verify 가 코드를 읽어 확인한다.
- `web/test/browser/sign-out.spec.ts`(신규): 로그아웃 전에 `/new` → `/chat/{id}` 로 두 번 옮긴 뒤 로그아웃 → `/signin`, `page.goBack()` 이 앞 화면이 아니라 로그인 화면, `/chat/{id}` 를 열어도 로그인 화면, `setSession` 으로 같은 사용자를 다시 넣으면 로그아웃 전에 만든 대화가 목록에 있음, `persisted: true` 인 `PageTransitionEvent('pageshow')` 를 보내면 문서를 다시 읽음, `/signout` 을 `page.route` 로 500 이나 `abort()` 로 만들면 안내와 화면 유지. 자기 context 의 세션만 지운다.
- `web/test/node/auth-signin.test.ts`: 기존 방식대로 provider 를 가짜로 바꿔 끼워 Google 설정에 `prompt: "select_account"` 가 있는지 본다.
- 기존 테스트의 옛 자리 고치기: `admin-area.spec.ts`, `nav.spec.ts`, `people.spec.ts`(`admin-entry` 는 메뉴를 연 뒤 찾는다), `design-tokens.spec.ts`, `loading.spec.ts`, `motion.spec.ts`, `dev-docs.spec.ts`, `tasks.spec.ts`, `now.spec.ts`(「지금 볼 것」 이 대화 검색 아래로 바뀐 위치 단언), `theme.spec.ts`, `workspace-files.spec.ts`(「고급」 의 「파일 공간」), `shell.spec.ts` 의 `/usage` 에서 출발하던 사례에서 「주요 화면」 의 사라진 링크와 밝기 단추를 찾는 곳.

### 8. 문서

- `web/docs/code-architecture.md` 「화면 틀」: 그림과 사이드바 구역 표를 새 차례로 고친다. 이름 메뉴를 적는다.
- `docs/features/attention.md`: 알림 단추의 자리(사이드바 맨 위)를 고친다.
- `docs/features/users.md` 「관리자 영역」: 관리자 입구를 이름 메뉴의 「관리자 화면」 으로 고친다.
- `docs/prd.md` 와 ADR-063 의 현재 원본 가운데 사이드바 관리자 입구를 적은 문장이 있으면 고친다. ADR 의 결정은 바꾸지 않는다.
- `web/docs/code-architecture.md` 의 밝기 값 이름을 「밝게, 어둡게, 기기 설정」 으로 고친다.
- `docs/features/app-shell.md`: 구현한 흐름을 적고 「아직 구현 전」 문장을 고친다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:node
cd web && node --test test/node/auth-signin.test.ts
cd web && HEAVY_SLOTS=2 orch slot heavy -- pnpm test:browser test/browser/shell.spec.ts test/browser/account-menu.spec.ts test/browser/sign-out.spec.ts test/browser/theme.spec.ts test/browser/nav.spec.ts test/browser/admin-area.spec.ts test/browser/people.spec.ts test/browser/design-tokens.spec.ts test/browser/loading.spec.ts test/browser/motion.spec.ts test/browser/dev-docs.spec.ts test/browser/tasks.spec.ts test/browser/now.spec.ts test/browser/workspace-files.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/components/shell/now-link.tsx` | 수정 (여백과 주석) |
| `web/src/lib/sign-out-client.ts` | 신규 (화면에서 `fetch` 를 직접 부르지 않는다) |
| `web/test/browser/helpers.ts` | 수정 (`openAccountMenu`) |
| `web/test/browser/workspace-files.spec.ts` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/shell/account-menu.tsx` | 신규 |
| `web/src/app/signout/route.ts` | 신규 |
| `web/src/auth.ts` | 수정 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/test/node/auth-signin.test.ts` | 수정 |
| `web/src/components/ui/theme-toggle.tsx` | 삭제 |
| `web/test/browser/account-menu.spec.ts` | 신규 |
| `web/test/browser/sign-out.spec.ts` | 신규 |
| `web/test/browser/shell.spec.ts` | 수정 |
| `web/test/browser/theme.spec.ts` | 수정 |
| `web/test/browser/nav.spec.ts` | 수정 |
| `web/test/browser/admin-area.spec.ts` | 수정 |
| `web/test/browser/people.spec.ts` | 수정 |
| `web/test/browser/design-tokens.spec.ts` | 수정 |
| `web/test/browser/loading.spec.ts` | 수정 |
| `web/test/browser/motion.spec.ts` | 수정 |
| `web/test/browser/dev-docs.spec.ts` | 수정 |
| `web/test/browser/tasks.spec.ts` | 수정 |
| `web/test/browser/now.spec.ts` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `docs/features/attention.md` | 수정 |
| `docs/features/users.md` | 수정 |
| `docs/features/app-shell.md` | 수정 |
