# Phase 01. 화면을 검증할 수단을 만들고 색과 밝기 모드를 정리한다

**Execution profile**: standard

## 목표

브라우저를 띄워 화면을 검증하는 테스트를 만들고,
인라인 스타일로 박혀 있는 색을 Tailwind 테마 토큰으로 옮기고,
사용자가 밝음과 어두움과 시스템 중에서 고를 수 있게 한다.

지금 저장소에는 화면을 검증할 방법이 없다.
`test/e2e` 는 API 만 부르고 브라우저를 띄우지 않는다.
그래서 `너비 390px 에서 입력창이 보인다` 같은 것을 확인할 수단이 없다.
뒤 phase 가 레이아웃을 크게 바꾸므로 이것부터 만든다.

지금은 색이 `style={{ background: "var(--surface)" }}` 처럼 90곳 넘게 인라인으로 적혀 있다.
인라인 스타일에는 `hover:` 와 `md:` 와 `disabled:` 를 붙일 수 없어서,
색 하나가 그 요소의 반응형과 상태 변화를 함께 막는다.
뒤 phase 의 레이아웃 작업이 이것부터 풀려야 진행된다.

**범위 외**

- 레이아웃은 바꾸지 않는다. phase-02 가 한다.
- 마크다운 렌더링은 phase-02 가 한다.
- 화면마다 부품을 나누는 것은 phase-02 와 phase-03 이 한다.

## 컨텍스트

웹은 Next.js 16 과 React 19 를 쓰고 Tailwind v4 로 꾸민다.
**Tailwind v4 는 `tailwind.config.js` 를 쓰지 않는다.** CSS 안의 `@theme` 에 토큰을 선언한다.

지금 `globals.css` 가 `:root` 와 `@media (prefers-color-scheme: dark)` 에 CSS 변수 다섯을 둔다.
`--background`, `--foreground`, `--muted`, `--surface`, `--border` 다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 지금 토큰 선언 | `web/src/app/globals.css` |
| 머리와 바깥 틀 | `web/src/app/layout.tsx` |
| 인라인 스타일이 가장 많은 곳 | `web/src/components/chat-panel.tsx` |
| 나머지 | `web/src/components/conversation-list.tsx`, `web/src/app/usage/page.tsx`, `web/src/app/admin/agents/agent-admin-panel.tsx` |

**근거 문서**: `docs/code-architecture.md` 의 「web 화면 구조」 절, `docs/flow.md` 의 「밝기 모드」 절

## 의도 메모

- 토큰 이름을 지금 쓰던 것에서 바꾸지 않는다. 한 번에 두 가지를 바꾸면 무엇이 깨졌는지 모른다.
  이름은 그대로 두고 선언 위치와 쓰는 방식만 옮긴다.
- 색을 늘리지 않는다. 다섯으로 부족한 자리가 나오면 그때 하나씩 더한다.
  미리 열 개를 만들어 두면 쓰이지 않는 이름이 남는다.
- 밝기 모드는 `class` 로 구분한다. `data-theme` 속성을 쓰지 않는다.
  Tailwind v4 의 `dark:` 변형이 클래스를 보게 설정하는 편이 단순하다.
- 첫 화면이 밝게 그려졌다가 어두워지지 않아야 한다.
  `next-themes` 가 그리기 전에 저장된 값을 읽어 붙인다. 그 처리를 직접 쓰지 않는다.

## 작업 항목

### 1. 의존을 더한다

```bash
# cwd: web
pnpm add next-themes
pnpm add -D @playwright/test
pnpm exec playwright install chromium
```

### 2. 브라우저 테스트 하네스를 만든다

`test/browser/` 를 만든다. `test/e2e/` 와 나란히 둔다.

지금 `test/e2e/run.ts` 가 가짜 Hermes 와 백엔드와 데이터베이스를 띄우고 API 를 부른다.
그 준비 과정을 그대로 쓰고 웹까지 띄운 뒤 브라우저를 붙인다.

- `test/browser/playwright.config.ts` 를 둔다.
  - 너비 `390` 과 `1280` 두 프로젝트로 나눈다. 이름을 `mobile` 과 `desktop` 으로 한다.
  - `webServer` 로 웹을 띄운다.
- 로그인은 실제 Google 을 거치지 않는다.
  **테스트에서만 켜지는 우회를 만들지 않는다.** 운영 코드에 시험용 문이 남는다.
  대신 NextAuth 세션 쿠키를 테스트가 직접 만들어 넣는다.
  `web/src/auth.ts` 가 쓰는 비밀값으로 서명한다.
- `test/browser/fixtures.ts` 가 그 준비를 맡는다.

`package.json` 에 `test:browser` 를 더한다.

**이 하네스가 뒤 phase 의 검증 수단이다.** 여기서 만든 것 위에 phase-02 와 phase-03 이 얹힌다.

### 3. `globals.css` 를 토큰 선언으로 바꾼다

- `@theme` 에 색 다섯을 선언해 `bg-surface` 와 `text-muted` 와 `border-border` 로 쓸 수 있게 한다.
- 어두운 값은 `.dark` 클래스 아래에서 다시 선언한다.
- `@media (prefers-color-scheme: dark)` 로 직접 판단하지 않는다. `next-themes` 가 그 판단을 한다.
- `dark:` 변형이 `.dark` 클래스를 보도록 설정한다. Tailwind v4 는 `@custom-variant` 로 정한다.
- `body` 는 `bg-background` 와 `text-foreground` 를 쓴다.

### 4. `next-themes` 를 붙인다

`web/src/components/theme-provider.tsx` 를 만든다.

- `next-themes` 의 provider 를 `attribute="class"` 와 `defaultTheme="system"` 으로 감싼다.
- 클라이언트 컴포넌트다.

`web/src/app/layout.tsx` 가 그것으로 본문을 감싼다.
`html` 에 `suppressHydrationWarning` 을 붙인다. 이것이 없으면 콘솔에 경고가 남는다.

### 5. 밝기 전환 단추를 만든다

`web/src/components/ui/theme-toggle.tsx` 를 만든다.

- 밝음과 어두움과 시스템을 돌아가며 고른다.
- 지금 무엇인지 아이콘과 `aria-label` 로 알린다.
- **서버에서 그릴 때는 무엇이 고른 값인지 알 수 없다.** `next-themes` 의 `useTheme` 가
  처음 그릴 때 `undefined` 를 준다. 그 사이에 자리만 잡고 아이콘을 비운다.
  이것을 하지 않으면 hydration 이 어긋난다.

머리에 둔다. 좁은 화면에서도 보여야 한다.

### 6. 인라인 스타일을 제거한다

아래 파일에서 `style={{ ... }}` 로 적은 색을 Tailwind 클래스로 바꾼다.

- `web/src/app/layout.tsx`
- `web/src/components/chat-panel.tsx`
- `web/src/components/conversation-list.tsx`
- `web/src/app/usage/page.tsx`
- `web/src/app/admin/agents/agent-admin-panel.tsx`

색이 아닌 인라인 스타일이 남아 있으면 그것은 그대로 둔다.
이 phase 는 색만 옮긴다.

고른 항목처럼 상태에 따라 색이 달라지는 자리는 조건부 클래스로 쓴다.

### 7. 이 phase 를 검증하는 브라우저 테스트

`test/browser/theme.spec.ts` 를 만든다.

- 밝기 단추를 눌러 `html` 의 `class` 가 `dark` 로 바뀐다.
- 새로 고쳐도 그 값이 남는다.
- 첫 그림에서 `html` 이 이미 `dark` 를 갖고 있다. 그린 뒤에 붙지 않는다.
  저장된 값을 넣어 두고 화면을 연 뒤 첫 스크린샷으로 확인한다.

`mobile` 과 `desktop` 두 폭에서 모두 돈다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일이 남아 있다" || echo "통과"
```

```bash
# cwd: 저장소 root
grep -rn 'prefers-color-scheme' web/src/ && echo "실패: 밝기 판단이 두 곳에 있다" || echo "통과"
```

```bash
# cwd: 저장소 root
cd web && pnpm test:browser
```

`mobile` 과 `desktop` 두 폭에서 모두 통과해야 한다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/package.json` | 수정. `next-themes`, `@playwright/test` |
| `test/browser/playwright.config.ts` | 신규 |
| `test/browser/fixtures.ts` | 신규 |
| `test/browser/theme.spec.ts` | 신규 |
| `web/src/app/globals.css` | 수정 |
| `web/src/components/theme-provider.tsx` | 신규 |
| `web/src/components/ui/theme-toggle.tsx` | 신규 |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/conversation-list.tsx` | 수정 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |

## 끝낸 뒤

`tasks/plan005-ui/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
