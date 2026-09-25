# Phase 02. shadcn 기반을 깔고 공통 부품과 아이콘을 옮긴다

**Execution profile**: standard

## 목표

shadcn/ui 를 저장소에 들이고, `components/ui/` 의 모양 부품과 글자 아이콘을 shadcn 부품과 `lucide-react` 로 바꾼다.
다음 phase 가 대화상자, 메뉴, 서랍을 이 부품으로 바꿀 수 있게 기반을 모두 깐다.

**범위 외**: 서랍, 대화 메뉴, 확인 창, 작업 과정 패널의 동작 교체(phase-03). 에이전트, 기억, 사용량, 관리 화면의 부품 교체(뒤 계획).
이 phase 는 화면 틀과 대화 화면 밖의 호출부도 `Button` 과 아이콘만은 함께 고친다. 옛 `Button` 서명이 남지 않게 하기 위해서다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`,
`docs/code-architecture.md` 의 「디렉터리」 와 「우리 화면의 정체성」, `web/AGENTS.md`.

앞 phase 가 토큰 이름을 shadcn 체계로 옮겼다. 그래서 shadcn 이 복사해 주는 부품의 클래스(`bg-primary`, `text-muted-foreground`, `ring-ring` 등)가 고치지 않고 우리 색으로 그려진다.

### 지금 있는 부품

계획을 세울 때(main `a5d48f9`) 읽은 모양이다. 구현 전에 다시 연다.

| 파일 | 지금 |
| --- | --- |
| `web/src/components/ui/button.tsx` | `variant` 는 `primary`, `secondary`, `ghost`, `size` 는 `sm`, `md`. 기본은 `primary`, `md`. `rounded-md`, `px-control-x-*`, `py-control-y-*`, `primary` 는 누르면 `brand-strong`(앞 phase 뒤로는 `primary-strong`) |
| `web/src/components/ui/icon-button.tsx` | `label` 을 `aria-label` 과 `title` 로 붙이는 `size-9` 테두리 단추. 계획 때 부르는 곳이 없었다 |
| `web/src/components/ui/badge.tsx` | `emphasis` 불린 하나로 두 모양 |
| `web/src/components/ui/skeleton.tsx` | `animate-pulse`, `motion-reduce:animate-none` |
| `web/src/components/ui/theme-toggle.tsx` | 글자 아이콘 `☀`, `☾`, `◐`. 접근성 이름은 `밝기 모드: …. 다음은 …` |
| `web/src/components/ui/empty-state.tsx`, `stat.tsx` | 모양만. 이 phase 에서 바꾸지 않는다 |

계획 때 `Button` 을 부르는 파일이 13개였고 `size="sm"` 16곳, `variant="secondary"` 10곳, `variant="ghost"` 5곳, `variant="primary"` 1곳이었다.

글자 아이콘과 직접 그린 `svg` 가 있던 곳이다.

| 파일 | 무엇 |
| --- | --- |
| `shell/app-shell.tsx` | 사이드바 열기와 펴기 `☰`, 새 대화 `✎` |
| `chat/composer.tsx` | 보내기와 중지의 `svg` 둘, 첨부 빼기 `×` |
| `chat/activity/activity-timeline.tsx` | 상태 표시 `⟳ ✓ ! ■` 와 끝나지 않음 |
| `chat/activity/activity-block.tsx`, `activity-panel.tsx` | 펼침 표시와 닫기 |
| `shell/conversation-nav.tsx` | 줄 메뉴 단추 |
| `usage/execution-table.tsx`, `usage/execution-card.tsx` | 나무로 가는 `▸` |
| `ui/theme-toggle.tsx` | `☀ ☾ ◐` |

대화 화면 개선이 그 뒤에 부품을 더했다. 아래로 지금 목록을 다시 만든다.

```bash
# cwd: 저장소 root
grep -rln 'components/ui/button\|ui/button"' web/src
grep -rhoE 'variant="[a-z-]+"|size="[a-z-]+"' web/src | sort | uniq -c
grep -rln 'IconButton' web/src
grep -rnE '[☰✓⟳✎▸▾◂▴⋯×✕■☀☾◐↑↓←→]' web/src/components web/src/app
grep -rn '<svg' web/src
```

## 의도 메모

- shadcn CLI 가 만든 파일은 그대로 두지 않는다. **우리 인상으로 고친다.** 모서리 `rounded-md`, 간격 `control` 토큰, 누름 `primary-strong` 이다.
  고친 곳에는 한국어 주석을 한 줄 단다. 나중에 다시 받을 때 무엇을 고쳤는지 대조하기 위해서다(ADR-023 「결과」).
- 새 부품 이름과 변형 이름은 shadcn 의 것을 쓴다. 옛 `primary`, `secondary`, `md` 를 별칭으로 남기지 않는다.
- 접근성 이름을 바꾸지 않는다. 아이콘만 있는 단추는 지금 `aria-label` 을 그대로 옮긴다. `title` 로 보이던 풀이는 phase-03 이 Tooltip 으로 옮긴다.
- 기각: 옛 `Button` 을 두고 새 부품을 `ShadButton` 처럼 따로 두기. 두 단추가 섞이면 이 계획이 끝나도 옛 것이 남는다.
- `lucide-react` 아이콘은 `aria-hidden` 이 기본이다. 아이콘만으로 뜻이 전해지는 곳(상태 표시)은 지금처럼 옆에 `sr-only` 글자를 둔다.

## Blocked 조건

- 앞 phase 가 끝나지 않았다(`globals.css` 에 `--primary` 가 없다) → `PHASE_BLOCKED: 토큰 이름이 아직 옮겨지지 않았다`

## 작업 항목

### 1. shadcn 기반을 깐다

```bash
# cwd: web/
pnpm dlx shadcn@latest init
```

- 물음에는 이 저장소에 맞게 답한다: TypeScript, `src/` 사용, 전역 CSS `src/app/globals.css`, CSS 변수 사용, 별칭 `@/components` 와 `@/lib/utils`
- `init` 이 `globals.css` 의 변수와 `@theme inline` 을 덮어쓰려 하면 받아들이지 않는다. 앞 phase 의 값과 이름을 그대로 남기고,
  `init` 이 더하려는 것 가운데 우리에게 없는 것(`@import "tw-animate-css";`, `--radius` 계열)만 손으로 옮긴다. `--radius` 는 지금 `--radius-md` 값에 맞춘다
- 결과로 `web/components.json` 과 `web/src/lib/utils.ts`(`cn()`)가 생긴다. `components.json` 의 `aliases.ui` 가 `@/components/ui`, `aliases.utils` 가 `@/lib/utils` 인지 본다
- 의존성은 CLI 가 고르는 이름을 그대로 둔다. ADR-023 의 목록(`@radix-ui/*` 또는 `radix-ui`, `class-variance-authority`, `clsx`, `tailwind-merge`, `tw-animate-css`, `lucide-react`)과 다른 것이 더해지면 이 phase 보고에 적는다
- `pnpm-lock.yaml` 이 바뀐다. 함께 커밋한다

### 2. 부품을 받는다

```bash
# cwd: web/
pnpm dlx shadcn@latest add button badge skeleton input textarea label dialog alert-dialog dropdown-menu sheet tooltip separator
```

`button.tsx`, `badge.tsx`, `skeleton.tsx` 는 이미 있다. CLI 가 덮어쓸지 물으면 덮어쓴다. 옛 내용은 git 이력에 있다.
`sonner`(알림 풍선)는 이 phase 에서 받지 않는다. 쓰는 화면이 생길 때 받는다.

### 3. 받은 부품을 우리 인상으로 고친다

| 부품 | 고칠 것 |
| --- | --- |
| `button.tsx` | `default` 의 누름과 마우스 올림을 `bg-primary-strong` 으로. `size` 의 `sm` 과 `default` 여백을 `px-control-x-sm py-control-y-sm`, `px-control-x-md py-control-y-md` 로. 모서리 `rounded-md`. `type` 기본값 `"button"` 을 지금처럼 둔다(폼 안에서 뜻하지 않게 보내지 않게) |
| `button.tsx` (보내는 중) | `loading?: boolean` 과 `loadingText?: string` 을 더한다. 참이면 `disabled` 와 `aria-busy="true"` 를 켜고, 글자 앞에 `Loader2`(`animate-spin`, `motion-reduce:animate-none`, `aria-hidden`)를 두고, 글자를 `loadingText` 로 바꾼다. 폭이 줄지 않게 `loadingText` 를 주지 않으면 원래 글자를 그대로 둔다. 근거는 `docs/flow.md` 「기다리는 동안 보이는 것」 이다 |
| `badge.tsx` | 지금의 두 모양을 `variant` 둘로 옮긴다: 흐린 것(`outline` 에 `bg-muted text-muted-foreground`)과 강조(`border-foreground font-semibold`). `emphasis` 속성은 지우고 부르는 곳을 `variant` 로 바꾼다 |
| `skeleton.tsx` | `motion-reduce:animate-none` 과 `aria-hidden` 을 지금처럼 둔다 |
| `input.tsx`, `textarea.tsx` | 모서리 `rounded-md`, 초점 테두리 `ring-ring` |
| 그 밖 | 받은 그대로 둔다. 토큰 이름이 같아 우리 색으로 그려진다 |

### 4. 옛 `Button` 을 부르는 곳을 모두 옮긴다

| 옛 | 새 |
| --- | --- |
| `variant="primary"` 또는 없음 | 없음(`default`) |
| `variant="secondary"` | `variant="outline"` |
| `variant="ghost"` | `variant="ghost"` |
| `size="md"` 또는 없음 | 없음(`default`) |
| `size="sm"` | `size="sm"` |
| `className="… !p-0"` 로 원형을 만든 곳 | `size="icon"` 과 `rounded-full` |

`className` 을 넘기던 곳은 `cn()` 이 합치므로 그대로 둔다. 겹치는 색 클래스가 있으면 뒤의 것이 이긴다는 것을 알고 확인한다.

`ui/icon-button.tsx` 를 부르는 곳이 있으면 `<Button variant="outline" size="icon" aria-label={label}>` 로 바꾸고 파일을 지운다.

### 5. 아이콘을 `lucide-react` 로 바꾼다

위 「지금 있는 부품」 의 명령으로 만든 목록을 모두 바꾼다. 아래는 대응이다. 목록에 없는 글자는 뜻이 같은 `lucide-react` 아이콘을 고른다.
`web/src/components/shell/nav-pending.tsx` 가 테두리로 그린 회전 표시도 `Loader2` 로 바꾼다.

| 글자 | 아이콘 |
| --- | --- |
| `☰` | `PanelLeft`(사이드바 펴기, 접기), `Menu`(좁은 화면의 서랍 열기) |
| `✎` | `SquarePen` |
| 보내기 `svg` | `ArrowUp` |
| 중지 `svg`, `■` | `Square` |
| `×`, `✕` | `X` |
| `⟳` | `LoaderCircle` 에 `animate-spin motion-reduce:animate-none` |
| `✓` | `Check` |
| `!` | `CircleAlert` |
| `▸`, `▾` | `ChevronRight`, `ChevronDown` |
| `⋯` | `Ellipsis` |
| `☀`, `☾`, `◐` | `Sun`, `Moon`, `SunMoon` |

아이콘 크기는 글자 크기에 맞춰 `size-4`, 큰 단추 안은 `size-5` 로 둔다. 상태 표시는 지금의 `sr-only` 글자를 그대로 둔다.

### 6. 이 phase 를 검증하는 테스트

기존 브라우저 테스트가 그대로 통과해야 한다. 접근성 이름을 바꾸지 않았으므로 `getByRole(…, { name })` 이 그대로 찾는다.

`test/browser/design-tokens.spec.ts` 에 두 경우를 더한다.

| 확인 | 기대 |
| --- | --- |
| 보내기 단추에 마우스를 올린다 | 바탕이 `--primary-strong` 의 색이다 |
| 어두움 모드의 `outline` 단추 | 테두리가 `--border` 의 색이고 바탕이 비어 있다 |

## 검증

```bash
# cwd: 저장소 root
test -f web/components.json && test -f web/src/lib/utils.ts
test ! -f web/src/components/ui/icon-button.tsx
grep -rn 'IconButton\|variant="primary"\|variant="secondary"\|size="md"\|emphasis' web/src
grep -rnE '[☰✓⟳✎▸▾⋯×✕■☀☾◐]' web/src/components web/src/app
grep -rn '<svg' web/src
grep -rn 'style={{' web/src/
scripts/check-public-safe.sh
```

`grep` 넷은 아무것도 내지 않아야 한다. 코드 주석 안의 글자와 사용자에게 보이는 문장 안의 글자(`·` 같은 구분점)는 예외이고, 나오면 하나씩 보고 판단한다.
`style={{` 는 까닭을 적은 주석이 달린 예외만 나와야 한다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser
```

그다음 AGENTS.md 「확인」 절의 명령을 적힌 순서대로 모두 돌린다. `pnpm build` 는 `web/AGENTS.md` 의 자리표시자 환경 변수가 필요하다.

끝나면 `tasks/plan023-design-foundation/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 3으로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/components.json` | 신규 |
| `web/src/lib/utils.ts` | 신규 |
| `web/package.json`, `web/pnpm-lock.yaml` | 수정 |
| `web/src/app/globals.css` | 수정. `tw-animate-css` 와 `--radius` |
| `web/src/components/ui/button.tsx` | 교체 |
| `web/src/components/ui/badge.tsx` | 교체 |
| `web/src/components/ui/skeleton.tsx` | 교체 |
| `web/src/components/ui/input.tsx`, `textarea.tsx`, `label.tsx`, `dialog.tsx`, `alert-dialog.tsx`, `dropdown-menu.tsx`, `sheet.tsx`, `tooltip.tsx`, `separator.tsx` | 신규 |
| `web/src/components/ui/icon-button.tsx` | 삭제 |
| `web/src/components/ui/theme-toggle.tsx` | 수정 |
| `web/src/**/*.tsx` | 수정. `Button` 과 `Badge` 를 부르는 곳, 글자 아이콘이 있는 곳 |
| `test/browser/design-tokens.spec.ts` | 수정 |
