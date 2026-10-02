# Phase 02. 닫히는 풀이가 `Esc` 를 가져가지 않게 한다

**Execution profile**: standard

## 목표

풀이(Tooltip)가 닫히는 움직임을 하는 동안 누른 `Esc` 가 대화 화면의 처리기에 닿게 한다.
지금은 그 `Esc` 를 풀이가 가져가 답이 중지되지 않는다. `test/browser/stop.spec.ts` 의 「입력칸에 초점이 있어도 Esc 로 답을 중지한다」 가 desktop 폭에서 가끔 30초를 넘겨 실패하는 까닭이 이것이다.

**범위 외**: 열려 있는 풀이의 `Esc`. 「중지」 만 넘기고 나머지는 풀이만 닫는 지금 동작을 그대로 둔다.

## 컨텍스트

**근거 문서**: `docs/frontend/shell.md` 의 「화면 틀」 절에서 `Esc` 의 차례를 적은 목록 2번.

원인은 CI 실패 실행의 trace 와 로컬 재현으로 확인했다.

- 풀이는 `web/src/components/ui/tooltip.tsx` 의 `TooltipProvider` 가 `delayDuration = 0` 이라 마우스가 올라가면 곧바로 열린다.
- 「보내기」 를 마우스로 누르면 마우스가 올라간 순간 풀이가 열렸다가 누르는 순간 닫힌다. 순서가 맞으면 열리지 않고, 느린 환경에서는 열렸다 닫힌다.
- 닫힌 풀이는 닫히는 움직임(`duration-fast`, 120ms)이 끝날 때까지 `data-state="closed"` 로 DOM 에 남는다. 그동안 Radix 의 `DismissableLayer` 가 살아 있어 `Esc` 를 받고 `preventDefault` 를 부른다.
- `web/src/components/chat/conversation-session.tsx` 의 `onEscape` 는 `event.defaultPrevented && !escapeOnlyClosedTooltip(event)` 면 아무것도 하지 않는다. 그래서 그 120ms 안에 누른 `Esc` 는 중지로 이어지지 않는다.
- 실패한 실행에서 `Esc` 를 누른 순간 「보내기」 풀이가 `data-state="closed"` 로 남아 있었고 중지 요청은 나가지 않았다.

`web/src/components/ui/tooltip-button.tsx` 가 이미 「풀이만 닫은 `Esc`」 를 표시해 넘기는 장치(`escapesPassedThrough`, `escapeOnlyClosedTooltip`, `passEscape`)를 갖고 있다. `TooltipContent` 를 직접 쓰는 곳은 이 파일뿐이다.

## 의도 메모

- 검사의 timeout 을 늘리거나 `Esc` 전에 기다리게 하지 않는다. 사용자가 마우스를 뗀 직후에 `Esc` 를 눌러도 같은 일이 생기므로 화면의 결함이다.
- 닫히는 움직임을 없애지 않는다. 움직임은 `globals.css` 의 토큰이 정한다.
- 모든 풀이에 `passEscape` 를 주지 않는다. 열려 있는 풀이는 `Esc` 로 풀이만 닫는 것이 지금의 약속이고, 바꾸면 사용자에게 보이는 동작이 달라진다.

## 작업 항목

### 1. `web/src/components/ui/tooltip-button.tsx` 의 `TooltipButton`

`TooltipContent` 의 `onEscapeKeyDown` 을 언제나 넘기고, 넘길지를 그 안에서 정한다.

```tsx
<TooltipContent
  onEscapeKeyDown={(event) => {
    // 닫히는 움직임을 하는 동안에도 풀이는 `Esc` 를 받는다. 사용자에게는 닫을 것이 없으므로 대화 화면에 넘긴다.
    if (passEscape || !open) escapesPassedThrough.add(event);
  }}
>
```

`open` 은 그 부품이 이미 가진 state 다. `escapeOnlyClosedTooltip` 의 주석과 `TooltipButton` 의 주석에 닫히는 중인 풀이도 넘긴다는 것을 한 줄씩 더한다.

### 2. 이 phase 를 검증하는 `test/browser/stop.spec.ts` 의 검사

「중지에 마우스를 올려 풀이가 열려 있어도 첫 Esc 로 답을 중지한다」 아래에 더한다.
닫히는 움직임을 길게 늘려 그 사이에 `Esc` 를 누르므로 시간에 기대지 않는다. 고치기 전 코드에서는 마지막 줄에서 실패한다.

```ts
test("닫히는 중인 풀이가 남아 있어도 Esc 로 답을 중지한다", async ({ page, hermes }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "마우스를 올려 여는 풀이는 넓은 화면에서만 확인한다");
  await hermes.holdNextRun();
  await beginHeldTurn(page, "닫히는 풀이 Esc 검사");
  await hermes.waitForHeldRun();
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toBeEnabled();
  // 닫히는 움직임을 늘려 풀이가 닫힌 채 남아 있는 동안 Esc 를 누른다.
  await page.addStyleTag({ content: '[data-slot="tooltip-content"]{animation-duration:5s !important}' });
  await page.getByRole("button", { name: "사이드바 접기" }).hover();
  await expect(page.getByRole("tooltip")).toHaveText("사이드바 접기");
  await page.mouse.move(600, 300, { steps: 10 });
  await expect(page.locator('[data-slot="tooltip-content"][data-state="closed"]')).toBeVisible();
  await page.getByRole("textbox", { name: "메시지" }).focus();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("stopped-mark").or(page.getByTestId("no-answer")).first()).toBeVisible({ timeout: 30_000 });
});
```

`page.mouse.move` 에 `steps` 를 주지 않으면 풀이가 닫히지 않는다. Radix 가 마우스가 풀이 쪽으로 가는 중인지 다음 움직임으로 판단하기 때문이다.

정상 경로는 같은 파일의 「입력칸에 초점이 있어도 Esc 로 답을 중지한다」 와 「중지에 마우스를 올려 풀이가 열려 있어도 첫 Esc 로 답을 중지한다」 가 본다.
열려 있는 풀이가 `Esc` 를 가져가는 지금 동작은 `test/browser/shell.spec.ts` 의 `Esc` 검사들이 본다.

## 검증

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
```

브라우저 검사는 돌리기 전에 지시문이 알려 준 대기 스크립트를 먼저 실행한다.

```bash
# cwd: web/
pnpm test:browser stop.spec.ts shell.spec.ts
pnpm test:browser stop.spec.ts --project desktop -g "Esc" --repeat-each 20
```

첫 명령은 두 폭에서 모두 통과한다. 둘째 명령은 `stop.spec.ts` 의 `Esc` 검사들을 20번씩 되풀이해 한 번도 실패하지 않아야 한다.

새 검사가 고치기 전 코드에서 실패하는지도 한 번 본다. `tooltip-button.tsx` 의 변경을 잠시 되돌리고 아래를 돌리면 실패해야 한다. 확인한 뒤 변경을 다시 넣는다.

```bash
# cwd: web/
pnpm test:browser stop.spec.ts --project desktop -g "닫히는 중인"
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/tooltip-button.tsx` | 수정 |
| `test/browser/stop.spec.ts` | 수정 |
