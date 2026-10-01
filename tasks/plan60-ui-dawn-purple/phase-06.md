# Phase 06. 움직임: 메시지 등장, 펼침, 시트와 대화상자, 목록

**Execution profile**: deep

## 목표

ADR-047 「움직임」 표의 움직임을 부품에 붙이고, 모든 움직임이 줄인 움직임 설정을 따르는지 검사로 지킨다.
조작에 화면이 부드럽게 반응하게 하려는 것이다.

**범위 외**: 화면 전환은 phase 07. 모션 라이브러리를 더하지 않는다. 목록 재배치 움직임은 하지 않는다. 테마 바꿈의 crossfade 는 하지 않는다.

## 컨텍스트

- phase 01 이 `globals.css` 에 `duration-fast`(120ms), `duration-base`(200ms), `duration-slow`(260ms), `ease-out`, `ease-spring` 토큰과 `@media (prefers-reduced-motion: reduce)` 블록을 두었다. 그 블록이 모든 animation 과 transition 을 100ms linear 로 줄이고 `tw-animate-css` 의 이동과 크기 변화를 끈다.
- `web/src/components/ui/dialog.tsx`, `alert-dialog.tsx`, `sheet.tsx`, `dropdown-menu.tsx`, `tooltip.tsx` 가 `tw-animate-css` 의 `animate-in`, `animate-out`, `fade-in-0`, `zoom-in-95`, `slide-in-from-*` 와 `duration-100`, `duration-200` 을 쓴다.
- phase 04 가 `AssistantRow` 를 만들어 기다림 점, 작업 과정 블록, 답이 같은 자리에 들어온다. `activity-block.tsx` 는 펼친 내용을 `expanded ? ... : null` 로 그린다.
- `web/src/components/chat/message-list.tsx` 의 `scrollToBottom` 이 `behavior: "smooth"` 를 쓴다.
- 브라우저 검사는 Playwright 의 `page.emulateMedia({ reducedMotion: "reduce" })` 로 줄인 움직임을 켠다.

**근거 문서**: `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` 의 「움직임」 절, `docs/flow.md` 의 「기다리는 동안 보이는 것」 절, `web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」 절

## 의도 메모

- 등장 움직임은 **새로 생긴 줄에만** 준다. 대화를 열 때 이미 있던 메시지가 한꺼번에 움직이면 안 된다.
- 높이 펼침은 `grid-template-rows` 0fr 에서 1fr 로 한다. `height: auto` 는 움직이지 않고, `interpolate-size` 는 Safari 가 지원하지 않는다.
- 삭제 움직임은 서버 요청이 성공한 뒤에 시작한다. 실패하면 줄이 그대로 남아야 한다.

## 작업 항목

### 1. `web/src/app/globals.css` 의 keyframes 와 유틸리티

`@theme inline` 에 `--animate-*` 값만 선언해 `animate-*` 클래스를 만들고, `@keyframes` 는 `@theme` 블록 밖에 둔다. `test/unit/design-tokens.test.ts` 의 `block()` 이 블록을 첫 `}` 까지만 읽어, 안에 keyframes 를 두면 기존 토큰 검사가 깨진다.

| 클래스 | keyframes | 길이와 곡선 |
| --- | --- | --- |
| `animate-message-user` | opacity 0 → 1, `translateY(6px) scale(0.96)` → none. `transform-origin: 100% 100%` | `duration-base`, `ease-spring`, `both` |
| `animate-message-assistant` | opacity 0 → 1, `translateY(4px)` → none | `duration-base`, `ease-out`, `both` |
| `animate-screen-in` | opacity 0 → 1, `translateY(8px)` → none | `duration-base`, `ease-out`, `both` |
| `animate-fade-in` | opacity 0 → 1 | `duration-fast`, linear, `both` |

줄인 움직임 블록에 이 네 keyframes 가 이동과 크기 변화를 하지 않도록 더한다. 방법은 그 블록 안에서 네 클래스의 `animation-name` 을 흐려짐만 하는 keyframes(`fade-in` 의 것)로 `!important` 로 바꾸는 것이다.

펼침 유틸리티를 둔다.

```css
@utility collapsible {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--duration-slow) var(--ease-out);
  &[data-open="true"] { grid-template-rows: 1fr; }
  & > * { min-height: 0; overflow: hidden; }
}
```

### 2. 메시지 등장

- 어느 줄이 움직이는지는 `turn.id` 의 모양으로 정한다. `chat-panel.tsx` 는 보낸 직후 임시 문자열 id 로 줄을 더하고, 답이 끝나면 `refreshMessages` 가 `setTurns(loaded)` 로 목록을 통째로 바꿔 숫자 id 가 된다.
  - 임시 문자열 id 인 내 말풍선(`data-testid="user-message"`)에 `animate-message-user` 를 준다.
  - `pending-assistant` 줄에 `animate-message-assistant` 를 준다.
  - 숫자 id 줄과 답이 흘러나오는 비서 줄(`assistant-` 로 시작하는 id)에는 등장 움직임을 주지 않는다. 그래야 대화를 열 때와 답이 저장될 때 다시 움직이지 않는다.
  - `MessageBubble` 에 prop 을 더하지 않고 `typeof turn.id === "string"` 으로 판정한다.
- 기다림에서 답으로: `AssistantRow` 의 아래 칸에서 기다림 점이 답 본문으로 바뀔 때 본문 쪽에 `animate-fade-in` 을 준다. 줄의 높이는 phase 04 가 맞춰 두었다.
- `turn-error` 와 `no-answer` 줄에 `animate-fade-in` 을 준다.
- `scrollToBottom` 의 `behavior` 를 `window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth"` 로 정한다.

### 3. 작업 과정 펼치기

`web/src/components/chat/activity/activity-block.tsx`:

- 펼친 내용을 조건부로 그리지 않고 `<div className="collapsible" data-open={expanded}>` 안에 늘 둔다. 안쪽 `<div>` 가 `border-t` 와 여백을 갖는다. 접힌 동안 안쪽에 `inert` 를 줘 초점이 들어가지 않게 한다.
- 저장된 블록의 나무는 지금처럼 처음 펼칠 때만 읽는다(`expanded` 조건을 지킨다).
- `atBottomRef` 를 되돌리는 주석과 동작(「접으면 스크롤 상자가 사라진다」)을 새 구조에 맞게 고친다. 접어도 상자가 남으므로, 접을 때 `scrollTop` 을 0 으로 돌리고 `atBottomRef` 를 `true` 로 둔다. 도는 중에 다시 펼치면 맨 아래로 따라가야 한다(`test/browser/activity-scroll.spec.ts` 가 이 동작을 검사한다).
- 화살표에 `transition-transform duration-base ease-out` 을 준다.
- 접힌 내용을 「보이지 않음」 으로 단언하던 검사는 `toBeHidden()` 이 통과하는지 확인한다. 높이 0 과 `overflow: hidden` 만으로는 Playwright 가 보인다고 볼 수 있으므로, 접힌 안쪽에 `aria-hidden` 과 함께 `invisible`(`visibility: hidden`)을 주고, 펼칠 때 바로 푼다. 접을 때는 높이 transition 이 끝난 뒤(`onTransitionEnd`)에 `invisible` 을 건다.

### 4. 시트, 대화상자, 메뉴

| 파일 | 바꿀 것 |
| --- | --- |
| `web/src/components/ui/sheet.tsx` | 덮개 `duration-base`. 본문은 `duration-slow ease-out` 이고 `slide-in-from-*-10` 을 전체 폭 밀기(`slide-in-from-left`, `slide-in-from-right`, `slide-in-from-top`, `slide-in-from-bottom` 과 짝이 되는 `slide-out-to-*`)로 바꾼다 |
| `web/src/components/ui/dialog.tsx`, `alert-dialog.tsx` | `duration-100` 을 `duration-base` 로, `zoom-in-95`/`zoom-out-95` 를 `zoom-in-[0.96]`/`zoom-out-[0.96]` 으로. 곡선 `ease-out` |
| `web/src/components/ui/dropdown-menu.tsx`, `tooltip.tsx` | `duration-100` 을 `duration-fast` 로 |

`duration-100`, `duration-200` 같은 숫자 길이가 `web/src` 에 남지 않아야 한다.
`test/browser/design-tokens.spec.ts` 의 `readBorder` 는 Sheet 의 transition 을 끄고 읽는다. 그대로 통과하는지 확인한다.

### 5. 목록 추가와 삭제

- `web/src/components/ui/use-exit.ts` 신규.

  ```ts
  /** 줄을 지우기 전에 나가는 움직임을 보인다. `leaving` 인 동안 줄에 `data-leaving` 을 주고, 끝나면 `remove` 를 부른다. */
  export function useExit(durationMs?: number): { leaving: boolean; exit(remove: () => void): void }
  ```

  `durationMs` 기본값은 120 이다. `prefers-reduced-motion` 이면 기다리지 않고 바로 `remove` 를 부른다. 언마운트되면 타이머를 지운다.
- `globals.css` 에 `[data-leaving="true"] { opacity: 0; transition: opacity var(--duration-fast) linear; }` 를 둔다.
- 쓰는 곳은 둘이다.
  - 기억 지우기: `web/src/components/memory/memory-item.tsx` 가 요청이 성공한 뒤 `onChanged()` 를 따로 부른다. 그 호출을 `exit(() => void onChanged())` 로 감싼다.
  - 대화 지우기: `web/src/components/shell/conversations-provider.tsx` 의 `remove` 가 `DELETE` 요청과 `setConversations(filter)` 를 한 번에 한다. 둘을 나눈다. `remove(id)` 는 요청만 보내고 지금처럼 실패를 던지거나 돌려준다. 새 함수 `drop(id)` 가 목록에서 그 줄을 뺀다. context 값에 `drop` 을 더한다. `remove` 를 부르는 곳은 `web/src/components/shell/conversation-nav.tsx` 의 지우기 확인 하나다. 거기서 `await remove(id)` 가 성공한 뒤 `exit(() => drop(id))` 를 부른다. 지운 대화를 보고 있었을 때 새 대화로 옮기는 지금 동작은 `drop` 뒤에 그대로 일어나야 한다.
  - 줄마다 `useExit` 을 따로 가져야 하므로, 대화 목록의 줄이 한 컴포넌트가 아니면 지우는 중인 id 하나를 상태로 두고 그 id 의 줄에 `data-leaving` 을 준다.
- 추가: 대화 목록의 새 줄과 기억 목록의 새 줄에 `animate-message-assistant` 를 준다. 처음 그릴 때 있던 줄에는 주지 않는다. 목록을 처음 그릴 때의 id 집합을 `useRef` 에 두고, 그 집합에 없는 id 의 줄에만 준다. 대화 목록은 목록을 처음 읽어 온 뒤에 집합을 채운다.

### 6. 검사

`test/browser/motion.spec.ts` 신규.

| 검사 | 방법 |
| --- | --- |
| 새로 보낸 내 말풍선이 등장 움직임을 갖는다 | 보낸 직후 `user-message` 의 `getComputedStyle(...).animationName` 이 `none` 이 아니고 `animationDuration` 이 `0.2s` 다 |
| 대화를 다시 열면 이미 있던 메시지는 움직이지 않는다 | 새로 고친 뒤 같은 요소의 `animationName` 이 `none` 이다 |
| 답이 끝난 뒤 내 말풍선이 다시 움직이지 않는다 | 답이 저장된 뒤 `user-message` 의 `getAnimations().length` 가 0 이다 |
| 작업 과정 블록을 펼치면 높이가 transition 으로 바뀐다 | `.collapsible` 의 `transitionProperty` 에 `grid-template-rows` 가 있고 `transitionDuration` 이 `0.26s` 다 |
| 줄인 움직임에서 말풍선이 이동하지 않는다 | `emulateMedia({ reducedMotion: "reduce" })` 뒤 보낸 말풍선의 `animationDuration` 이 `0.1s` 이고 `animationName` 이 흐려짐만 하는 keyframes 의 이름이다 |
| 줄인 움직임에서 대화상자가 크기 변화를 하지 않는다 | 대화 지우기 확인 창을 열고 `[data-slot="alert-dialog-content"]` 의 `getComputedStyle(...).getPropertyValue("--tw-enter-scale")` 이 `1` 이다. 줄이지 않았을 때는 `1` 이 아니다 |
| 줄인 움직임에서 서랍이 밀려 들어오지 않는다 | `mobile` 에서 사이드바 서랍(`[data-slot="sheet-content"]`)의 `--tw-enter-translate-x` 계산값이 `0` 이나 `0px` 이다. 줄이지 않았을 때는 그렇지 않다 |
| 대화를 지우면 줄이 나가는 움직임을 거쳐 사라진다 | 지우기를 확인한 뒤 그 줄이 `data-leaving="true"` 를 가졌다가 목록에서 없어진다. `MutationObserver` 를 `addInitScript` 로 걸어 그 속성이 한 번 붙었는지 기록한다 |
| 지우기가 실패하면 줄이 남는다 | 지우기 요청(`DELETE /api/chat/conversations/*`)을 `page.route` 로 500 으로 돌려준다. 줄이 그대로 보이고 `data-leaving` 속성이 없다 |
| 줄인 움직임에서 「새 메시지」 단추가 바로 내려간다 | `scrollTo` 를 `addInitScript` 로 감싸 받은 `behavior` 가 `auto` 다 |

`test/unit/design-tokens.test.ts` 에 더한다: `web/src` 에 `duration-100`, `duration-150`, `duration-200`, `duration-300` 이 없다. `motion-reduce:` 는 `animate-none` 과 `hidden`, `flex` 에만 붙는다(`waiting-indicator.tsx` 의 대체 문장 포함).

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/design-tokens.test.ts
! grep -rnE 'duration-(100|150|200|300)\b' web/src
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/motion.spec.ts test/browser/activity-scroll.spec.ts
cd web && pnpm test:browser
```

기대값: 모두 종료 코드 0. 마지막 줄은 전체 브라우저 검사다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/browser/motion.spec.ts` | 신규 |
| `web/src/components/ui/use-exit.ts` | 신규 |
| `web/src/app/globals.css` | 수정 |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `web/src/components/memory/memory-item.tsx` | 수정 |
| `web/src/components/memory/memory-list.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/activity/activity-block.tsx` | 수정 |
| `web/src/components/ui/sheet.tsx` | 수정 |
| `web/src/components/ui/dialog.tsx` | 수정 |
| `web/src/components/ui/alert-dialog.tsx` | 수정 |
| `web/src/components/ui/dropdown-menu.tsx` | 수정 |
| `web/src/components/ui/tooltip.tsx` | 수정 |
| `test/unit/design-tokens.test.ts` | 수정 |
| `test/browser/activity-scroll.spec.ts` | 수정 |
| `test/browser/flow-progress.spec.ts` | 수정 |
