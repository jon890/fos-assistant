# Phase 02. 「설정」 화면과 설정 틀을 만든다

**Execution profile**: standard

## 목표

이름 메뉴의 「설정」 이 여는 화면 한곳을 만들고, 설정 화면들을 사이드바 대신 설정 틀 안에서 보인다.

**범위 외**: 화면들의 머리와 폭 맞추기(PR5), 화면 안쪽 구성.

## 컨텍스트

**요구사항**: REQ4, REQ5

**근거 문서**: `docs/features/app-shell/spec.md`, `docs/features/app-shell/design.md`

- 관리자 틀 `web/src/components/shell/admin-shell.tsx` 가 따를 본보기다. 경로로 고른 줄(`selected`), 돌아갈 곳(`readBackHref`, `LAST_CONVERSATION_KEY`), 넓은 화면 세로 메뉴와 본문.
- 틀은 `app-shell.tsx` 의 `ShellBody` 가 경로로 고른다(`isAdminArea`). 로그인 여부는 지금 `pathname !== "/signin" && pathname !== "/privacy"` 다.
- 레이아웃 `web/src/app/layout.tsx` 가 `readMe()` 로 역할을 읽어 `AppShell` 에 넘긴다.

## 의도 메모

- 넓이는 CSS(`md:`)로만 나눈다(설계 DECISION2). `useMediaQuery` 로 목록과 본문을 고르지 않는다.
- page 파일을 라우트 묶음으로 옮기지 않는다(설계 DECISION1).

## 작업 항목

### 1. `web/src/components/shell/back-to-chat.ts`(신규)

`admin-shell.tsx` 의 `LAST_CONVERSATION_KEY`, `readBackHref`, `subscribeNothing` 을 옮기고 `useBackToChatHref()` 훅으로 내보낸다. `admin-shell.tsx` 와 `app-shell.tsx` 는 여기서 가져온다. 이동만 하는 커밋으로 먼저 나눈다.

### 2. `web/src/components/shell/settings-area.ts`(신규)

- `SETTINGS_GROUPS`: 설계의 「묶음 목록」 표 그대로의 배열(`{ label, links: { href, label, icon }[] }`).
- `isSettingsArea(pathname, hasSession)`: 설계의 「틀을 고르는 규칙」.
- `selectedSettingsHref(pathname)`: `/settings` → `/agents`, `/executions/...` → `/usage`, 그 밖은 접두가 맞는 줄.

### 3. `web/src/components/shell/settings-shell.tsx`(신규)

`SettingsShell({ children, ready })`. 머리(넓은 화면: 제목 「설정」 과 오른쪽 「대화로」, 휴대폰의 `/settings` 밖: 왼쪽 「설정」 으로 가는 뒤로 가기 단추), 묶음 목록(`nav aria-label="설정 메뉴"`, 묶음마다 `role="group"` 과 이름), 본문 `main`. 휴대폰에서 `/settings` 면 본문을 `max-md:hidden`, 그 밖이면 목록을 `max-md:hidden`. `/settings` 에서 「에이전트」 줄의 골라진 표시는 `md:` 클래스로만 주고 `aria-current` 는 붙이지 않는다(휴대폰 목록 화면에는 지금 화면이 없다).

### 4. `web/src/components/shell/app-shell.tsx` 와 `web/src/app/layout.tsx`

레이아웃이 `auth()` 로 세션이 있는지 읽어 `hasSession` 을 넘긴다. `signedIn` 은 `/signin` 이 아니고 `/privacy` 면 `hasSession` 일 때다. `ShellBody` 는 관리자 영역 다음에 `isSettingsArea` 면 `SettingsShell` 을 그린다. 단축키는 사이드바 틀에서만 켠다.

### 5. `web/src/app/settings/page.tsx`(신규)

`app/agents/page.tsx` 의 기본 내보내기를 그대로 그린다(`searchParams` 를 넘긴다).

### 6. `web/src/app/privacy/page.tsx`

`auth()` 로 세션이 있으면 「로그인 화면으로」 단추와 「로그인 전에」 문장을 그리지 않는다.

### 7. 테스트

- `web/test/node/settings-area.test.ts`(신규): 경로 판정과 골라진 줄. `/settings`, `/agents/x`, `/executions/1`, `/privacy`(세션 있음과 없음), `/tasks`, `/admin/usage`.
- `web/test/browser/settings.spec.ts`(신규): 넓은 화면 `/settings` 의 묶음 넷과 일곱 줄의 차례, 「에이전트」 줄의 골라진 표시(`data-selected` 속성)와 에이전트 제목, 로그인한 `/privacy` 에 「로그인 화면으로」 와 「로그인 전에」 가 없음, 보던 대화가 없을 때 「대화로」 가 `/`, `/now` 에 사이드바, 360px `/settings` 의 목록만, 줄을 눌러 화면 열기와 뒤로 가기, `/connections` 를 바로 열면 그 줄이 골라짐, `/executions/{id}` 의 「사용량」, 로그인한 `/privacy` 가 설정 틀, 묶음 목록에 「예약 작업」 과 「기억」 없음, 대화 하나를 본 뒤 설정 → 「대화로」 가 그 대화, `/tasks` 와 `/memory` 에 사이드바.
- 묶음 목록 `nav` 는 `main` 밖에 둔다. 설정 목록의 링크에도 `NavPending` 을 붙인다.
- phase-01 에서 주소로 바로 열게 바꾼 테스트 가운데 사이드바를 기대하던 설정 화면 테스트(`loading.spec.ts`, `motion.spec.ts`, `dev-docs.spec.ts`, `workspace-files.spec.ts`, `user-browser.spec.ts`)를 설정 틀 기준으로 고친다.

### 8. 문서

- `web/docs/code-architecture.md`: 화면 목록에 `/settings` 를 더하고, 「설정 틀」 절을 「관리자 영역의 틀」 옆에 더한다. `/browser`, `/files`, `/dev-docs` 의 「사이드바 「고급」 의 …」 문장을 고친다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:node
cd web && node --test test/node/settings-area.test.ts
cd web && HEAVY_SLOTS=2 orch slot heavy -- pnpm test:browser test/browser/settings.spec.ts test/browser/loading.spec.ts test/browser/motion.spec.ts test/browser/dev-docs.spec.ts test/browser/workspace-files.spec.ts test/browser/user-browser.spec.ts test/browser/admin-area.spec.ts test/browser/privacy-page.spec.ts test/browser/shell.spec.ts test/browser/now.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/back-to-chat.ts` | 신규 |
| `web/src/components/shell/admin-shell.tsx` | 수정 |
| `web/src/components/shell/settings-area.ts` | 신규 |
| `web/src/components/shell/settings-shell.tsx` | 신규 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/app/settings/page.tsx` | 신규 |
| `web/src/app/privacy/page.tsx` | 수정 |
| `web/test/node/settings-area.test.ts` | 신규 |
| `web/test/browser/settings.spec.ts` | 신규 |
| `web/test/browser/shell.spec.ts` | 수정 |
| `web/test/browser/now.spec.ts` | 수정 |
| `web/test/browser/loading.spec.ts` | 수정 |
| `web/test/browser/motion.spec.ts` | 수정 |
| `web/test/browser/dev-docs.spec.ts` | 수정 |
| `web/test/browser/workspace-files.spec.ts` | 수정 |
| `web/test/browser/user-browser.spec.ts` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
