# Phase 01. 색, 모서리, 움직임 토큰과 전역 규칙

**Execution profile**: deep

## 목표

`web/src/app/globals.css` 의 토큰을 「새벽 보라」 값으로 바꾸고 의미 색, 모서리, 움직임 토큰과 줄인 움직임 전역 규칙을 더한다.
뒤 phase 의 부품과 움직임이 이 토큰만 쓰게 하려는 것이다.

**범위 외**: `Badge` 변형과 `Notice` 부품은 phase 02. 스위치와 모서리 클래스 정리는 phase 03. 움직임을 부품에 붙이는 것은 phase 06.

## 컨텍스트

- 토큰은 `web/src/app/globals.css` 하나에 있다. `@theme inline` 이 `--color-*` 를 `:root` 와 `.dark` 의 변수로 잇는다. Tailwind 4 이고 설정 파일이 없다.
- 값의 표는 ADR-047 의 「팔레트」, 「모서리」, 「움직임」 절에 있다. 그 표를 그대로 옮긴다. hex 로 적는다. `oklch()` 를 쓰지 않는다.
- `test/unit/design-tokens.test.ts` 가 `globals.css` 소스에서 토큰 이름을 읽고, `test/browser/design-tokens.spec.ts` 가 두 밝기 모드의 값을 읽는다. 두 파일 모두 `TOKENS` 목록을 갖는다.
- `test/browser/color.ts` 의 `parseRgb` 와 `contrast` 가 대비 계산을 갖는다. hex 와 `rgb()` 만 읽는다.

**근거 문서**: `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md`, `docs/code-architecture.md` 의 「우리 화면의 정체성」 절, `web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」 절

## 의도 메모

- `card` 와 `popover` 를 바탕과 다른 값으로 둔다. 층을 선이 아니라 표면으로 나누려는 것이다.
- `accent` 와 `secondary` 는 `border` 와 같은 값이다. `muted` 인 사이드바 위에서도 마우스 올림이 보여야 해서다.
- 강조 색을 글자에 쓰던 곳을 이 phase 에서 글자색으로 돌린다. 토큰만 바꾸면 보라 글자가 남는다.
- 색 값을 박은 검사를 토큰과 비교하도록 고친다. 새 값을 다시 박지 않는다.

## 작업 항목

### 1. `web/src/app/globals.css` 의 토큰

- `:root` 와 `.dark` 의 색 변수를 ADR-047 「팔레트」 표의 값으로 바꾼다. `code-*` 여섯은 그대로 둔다.
- 새 변수와 `@theme inline` 의 `--color-*` 를 더한다: `foreground-soft`, `primary-soft-foreground`, `signal`, `pill`, `pill-foreground`, `destructive-soft`, `success`, `success-soft`, `warning`, `warning-soft`, `info`, `info-soft`.
- 모서리: `--radius-sm` 0.25rem, `--radius-md` 0.75rem, `--radius-lg` 1rem, `--radius-xl` 1.25rem, `--radius-2xl` 1.5rem, `--radius-full` 9999px. `:root` 의 `--radius` 도 0.75rem 으로 맞춘다.
- 그림자: `:root` 에 `--card-shadow: 0 1px 2px rgba(23,19,31,.06), 0 8px 24px rgba(61,35,194,.06)`, `.dark` 에 `--card-shadow: 0 1px 2px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.35)` 를 두고 `@theme inline` 에서 `--shadow-card: var(--card-shadow)` 로 잇는다. 그림자는 색 토큰이 아니라 rgba 를 쓴다. `web/src/components/chat/ask-card.tsx` 의 묻는 카드 바깥에 `bg-card shadow-card` 를 준다(지금 `bg-muted/40`). 답을 기다리는 카드가 한 층 위에 떠 보이게 하는 자리다.
- 움직임: `@theme inline` 에 `--duration-fast: 120ms`, `--duration-base: 200ms`, `--duration-slow: 260ms`, `--ease-out: cubic-bezier(0.22, 1, 0.36, 1)`, `--ease-spring: cubic-bezier(0.34, 1.36, 0.64, 1)` 를 둔다.
  Tailwind 가 `duration-fast` 클래스를 만드는 이름은 `--transition-duration-*` 다. 그래서 `--transition-duration-fast: var(--duration-fast)`, `--transition-duration-base: var(--duration-base)`, `--transition-duration-slow: var(--duration-slow)` 를 함께 선언한다. `@utility` 로 직접 만들지 않는다. 그러면 `--tw-duration` 이 빠져 `animate-in` 의 길이가 맞지 않는다.
- 줄인 움직임 블록 하나를 둔다.

  ```css
  @media (prefers-reduced-motion: reduce) {
    *, ::before, ::after {
      animation-duration: 100ms !important;
      animation-timing-function: linear !important;
      animation-iteration-count: 1 !important;
      transition-duration: 100ms !important;
      transition-timing-function: linear !important;
      scroll-behavior: auto !important;
    }
  }
  ```

  같은 블록에서 `tw-animate-css` 의 들어오고 나가는 움직임이 이동과 크기 변화를 하지 않게 한다. 그 패키지의 keyframes 는 `--tw-enter-scale`, `--tw-enter-translate-x`, `--tw-enter-translate-y`, `--tw-exit-scale`, `--tw-exit-translate-x`, `--tw-exit-translate-y` 변수를 읽는다. `web/node_modules/tw-animate-css` 의 CSS 를 열어 변수 이름을 확인하고, 줄인 움직임에서 scale 은 1, translate 는 0 이 되도록 `!important` 로 덮는다. 흐려짐만 남는다.
- 한국어 줄바꿈: `body` 에 `word-break: keep-all; overflow-wrap: anywhere;` 를 둔다. `pre`, `code` 는 `word-break: normal` 로 되돌린다. 이미 `break-words`, `break-all` 을 적은 곳은 그 클래스가 이긴다.
- `html` 에 `background-color: var(--background); color: var(--foreground);` 가 이미 다른 곳(`layout.tsx` 의 `body` 클래스)에서 걸리는지 확인하고 그대로 둔다.

### 2. 강조 색과 하드코딩 색을 쓰던 곳

| 파일 | 지금 | 바꿀 것 |
| --- | --- | --- |
| `web/src/components/ui/button.tsx` 의 `link` 변형 | `text-primary underline-offset-4 hover:underline` | `text-foreground underline underline-offset-4 hover:text-foreground-soft` |
| `web/src/components/ui/badge.tsx` 의 `link` 변형 | `text-primary ...` | 위와 같다 |
| `web/src/components/admin/agent-card.tsx` 의 「상세 보기」 | `text-primary` | `text-foreground underline underline-offset-4` |
| `web/src/components/connector/connector-connection-panel.tsx` 의 `text-primary` 두 곳 | 링크 | 위와 같다 |
| `web/src/components/usage/monthly-summary.tsx` | `<span className="text-primary">` | 감싼 span 을 없앤다 |
| `web/src/components/chat/variants.ts` 의 `assistantAvatar` | `bg-primary text-primary-foreground` | `bg-muted text-foreground` |
| `web/src/app/signin/page.tsx` 의 원 | `bg-primary text-primary-foreground` | `bg-muted text-foreground`. 카드 바탕이 `bg-muted` 면 카드를 `bg-card` 로 바꿔 원이 보이게 한다 |
| `web/src/components/chat/waiting-indicator.tsx` 의 점 셋 | `text-primary` | `text-foreground-soft` |
| `web/src/components/chat/composer.tsx` 의 회전 표시 `text-primary` | 진행 표시 | `text-foreground` |
| `web/src/components/chat/message-bubble.tsx`, 그 밖의 `outline-primary` | 초점 테두리 | `outline-ring` |
| `web/src/components/chat/composer.tsx` 의 `focus-within:border-primary` | 입력창 초점 | `focus-within:border-ring` |
| `web/src/components/chat/agent-picker.tsx`, `web/src/components/chat/ask-card.tsx` 의 고른 칸 `border-primary` | 고른 것 | `border-primary bg-primary-soft text-primary-soft-foreground`. 고른 것은 강조 색을 쓰는 곳이다 |
| `web/src/components/memory/memory-form.tsx`, `web/src/components/memory/memory-item.tsx` 의 `accent-primary` | 체크박스 | 그대로 둔다. 고른 것이다 |
| `web/src/components/chat/artifact/artifact-panel.tsx` 의 `bg-white` | iframe 바탕 | 그대로 둔다. 에이전트가 만든 HTML 은 흰 바탕을 전제로 하므로, 까닭을 주석으로 한 줄 적는다 |
| `web/src/components/ui/dialog.tsx`, `sheet.tsx`, `alert-dialog.tsx` 의 덮개 | 주석에만 `bg-black/10` 이 남아 있다 | 주석에서 옛 값 언급을 지운다. 클래스는 `bg-foreground/35` 그대로다 |

카드와 대화상자와 메뉴가 `bg-card`, `bg-popover` 를 쓰는지 `web/src/components/ui/card.tsx`, `dialog.tsx`, `alert-dialog.tsx`, `dropdown-menu.tsx`, `sheet.tsx` 에서 확인한다. `bg-background` 로 적혀 있으면 `bg-card` 나 `bg-popover` 로 바꾼다. 사이드바 서랍은 `bg-muted` 그대로다.
대화 입력창(`composer.tsx` 의 `rounded-3xl` 줄)은 `bg-card` 에 `border-input` 을 쓴다.
`Input`, `Textarea`, `NativeSelect` 는 이미 `border-input` 이다. `Button` 의 `outline` 변형은 `border-border` 그대로 둔다(`design-tokens.spec.ts` 가 그 값을 검사한다).

### 3. 검사

- `test/unit/design-tokens.test.ts` 와 `test/browser/design-tokens.spec.ts` 의 `TOKENS` 에 새 토큰 이름을 더한다. 주석의 「ADR-023 의 표」 를 「ADR-023 과 ADR-047 의 표」 로 고친다.
- `test/unit/design-tokens.test.ts` 에 검사를 더한다: `globals.css` 에 `prefers-reduced-motion: reduce` 블록이 있다, `--duration-fast`, `--duration-base`, `--duration-slow`, `--ease-out`, `--ease-spring` 이 선언돼 있다, `word-break: keep-all` 이 있다, 색 변수 값이 모두 hex 다(`oklch(` 가 없다).
- `test/browser/chat.spec.ts` 62~63줄과 `test/browser/nav.spec.ts` 68~69줄: `rgb(176, 90, 60)` 과 `#b05a3c` 를 박은 단언을 `expect(parseRgb(background)).toEqual(parseRgb(primary))` 로 바꾼다. `parseRgb` 는 `./color.ts` 에서 가져온다.
- `test/browser/theme.spec.ts` 29줄: `rgb(16, 18, 22)` 대신 `html` 의 `--background` 값을 읽어 `parseRgb` 로 비교한다. 이 검사는 스크립트를 막으므로 `getComputedStyle(document.documentElement).getPropertyValue("--background")` 를 `body` 의 `evaluate` 안에서 함께 읽는다.
- `test/browser/design-tokens.spec.ts` 에 대비 검사를 더한다. 두 밝기 모드에서 아래 짝을 `contrast` 로 계산한다.

  | 글자나 선 | 바탕 | 기준 |
  | --- | --- | --- |
  | `--foreground`, `--foreground-soft`, `--muted-foreground` | `--background`, `--card`, `--muted` | 4.5 이상 |
  | `--primary-foreground` | `--primary`, `--primary-strong` | 4.5 이상 |
  | `--primary-soft-foreground` | `--primary-soft` | 4.5 이상 |
  | `--success`, `--warning`, `--info`, `--destructive` | 각자의 `-soft`, `--background`, `--card` | 4.5 이상 |
  | `--pill-foreground` | `--pill` | 4.5 이상 |
  | `--input`, `--ring` | `--background`, `--card` | 3 이상 |

- 같은 파일에 사이드바 메뉴 글자 검사를 더한다: 밝음에서 `aside[aria-label="사이드바"]` (모바일은 서랍) 안 메뉴 링크의 글자색과 사이드바 바탕색의 대비가 4.5 이상이다.
- 같은 파일에 입력칸 검사를 더한다: 대화 입력창을 감싼 요소의 테두리 색이 `--input` 이다. 기존 「어두움 모드의 outline 단추」 검사는 그대로 통과해야 한다.
- `test/browser/identity.spec.ts` 의 제목 「테마별 브랜드 색」 은 그대로 둬도 통과한다. 단언을 고칠 필요가 없는지 돌려서 확인한다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/design-tokens.test.ts
! grep -rnE 'rgb\(176, 90, 60\)|#b05a3c|rgb\(16, 18, 22\)' test/browser
! grep -rnE 'text-primary([^-a-z]|$)' web/src
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/design-tokens.spec.ts test/browser/theme.spec.ts test/browser/identity.spec.ts test/browser/chat.spec.ts test/browser/nav.spec.ts
```

기대값: 모두 종료 코드 0. 브라우저 검사는 `mobile` 과 `desktop` 둘 다 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/globals.css` | 수정 |
| `web/src/app/signin/page.tsx` | 수정 |
| `web/src/components/ui/button.tsx` | 수정 |
| `web/src/components/ui/badge.tsx` | 수정 |
| `web/src/components/ui/card.tsx` | 수정 |
| `web/src/components/ui/dialog.tsx` | 수정 |
| `web/src/components/ui/sheet.tsx` | 수정 |
| `web/src/components/ui/alert-dialog.tsx` | 수정 |
| `web/src/components/ui/dropdown-menu.tsx` | 수정 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `web/src/components/chat/variants.ts` | 수정 |
| `web/src/components/chat/waiting-indicator.tsx` | 수정 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/agent-picker.tsx` | 수정 |
| `web/src/components/chat/ask-card.tsx` | 수정 |
| `web/src/components/chat/artifact/artifact-panel.tsx` | 수정 |
| `test/unit/design-tokens.test.ts` | 수정 |
| `test/browser/design-tokens.spec.ts` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `test/browser/nav.spec.ts` | 수정 |
| `test/browser/theme.spec.ts` | 수정 |
