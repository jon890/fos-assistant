"use client";

import { useState, type ComponentProps } from "react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

/** 코드가 초점을 옮기는 동안만 참이다. 그 한 번의 focus 로는 Tooltip 을 열지 않는다 */
let focusingWithoutTooltip = false;

/**
 * 풀이를 열지 않고 초점을 준다.
 *
 * <p>메뉴나 창이 닫히며 초점을 단추로 되돌리거나, 창이 열리며 첫 단추에 초점을 줄 때 쓴다. 그때 Tooltip 이 열리면
 * 다음 `Esc` 를 Tooltip 이 가져가 대화 화면의 `Esc` 가 한 번 밀린다. 마우스를 올리거나 `Tab` 으로 들어올 때는
 * 지금처럼 연다. `focus` 사건은 `focus()` 안에서 곧바로 돌므로 표시는 그 동안만 세워 둔다.
 * Radix Tooltip 이 `focus` 를 받은 그 자리에서 열림을 알린다는 것에 기댄다. `radix-ui` 를 올리면
 * `shell.spec.ts` 의 메뉴와 지우기 창 `Esc` 검사를 desktop 폭에서 함께 돌려 이 성질이 남았는지 본다.
 */
export function focusWithoutTooltip(element: HTMLElement | null | undefined): void {
  if (!element) return;
  focusingWithoutTooltip = true;
  try {
    element.focus();
  } finally {
    focusingWithoutTooltip = false;
  }
}

/** 풀이만 닫고 대화 화면에 넘길 `Esc` 사건이다 */
const escapesPassedThrough = new WeakSet<Event>();

/**
 * 이 `Esc` 가 풀이만 닫았는지 알린다. 참이면 기본 동작이 막혀 있어도 대화 화면이 처리한다.
 *
 * <p>Radix 는 풀이를 `Esc` 로 닫을 때 반드시 `preventDefault` 를 부른다. `onEscapeKeyDown` 에서 막지 않으면
 * 스스로 막고 닫으며, 막으면 닫지 않는다. 그래서 기본 동작으로는 풀이만 닫은 사건을 가려낼 수 없어 따로 적어 둔다.
 */
export function escapeOnlyClosedTooltip(event: Event): boolean {
  return escapesPassedThrough.has(event);
}

/**
 * 글자 없이 아이콘만 있는 단추다. 접근성 이름과 마우스를 올렸을 때의 풀이 글이 같은 `label` 에서 나온다.
 *
 * <p>`title` 속성은 쓰지 않는다. 풀이는 Tooltip 이 보인다. 화면 틀 맨 위의 `TooltipProvider` 안에서 쓴다.
 * `passEscape` 를 주면 풀이가 열려 있어도 첫 `Esc` 가 풀이를 닫으며 대화 화면에도 간다. 「중지」 가 그렇다.
 * 마우스를 올려 둔 채 `Esc` 를 눌렀는데 풀이만 닫히고 답이 계속 흐르면 안 된다.
 */
export function TooltipButton({ label, children, variant = "ghost", size = "icon-sm", passEscape = false, ...props }:
  ComponentProps<typeof Button> & { label: string; passEscape?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <Tooltip open={open} onOpenChange={(next) => {
      if (next && focusingWithoutTooltip) return;
      setOpen(next);
    }}>
      <TooltipTrigger asChild>
        <Button variant={variant} size={size} aria-label={label} {...props}>{children}</Button>
      </TooltipTrigger>
      <TooltipContent onEscapeKeyDown={passEscape ? (event) => { escapesPassedThrough.add(event); } : undefined}>
        {label}
      </TooltipContent>
    </Tooltip>
  );
}
