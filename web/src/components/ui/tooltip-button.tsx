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

/**
 * 글자 없이 아이콘만 있는 단추다. 접근성 이름과 마우스를 올렸을 때의 풀이 글이 같은 `label` 에서 나온다.
 *
 * <p>`title` 속성은 쓰지 않는다. 풀이는 Tooltip 이 보인다. 화면 틀 맨 위의 `TooltipProvider` 안에서 쓴다.
 */
export function TooltipButton({ label, children, variant = "ghost", size = "icon-sm", ...props }:
  ComponentProps<typeof Button> & { label: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Tooltip open={open} onOpenChange={(next) => {
      if (next && focusingWithoutTooltip) return;
      setOpen(next);
    }}>
      <TooltipTrigger asChild>
        <Button variant={variant} size={size} aria-label={label} {...props}>{children}</Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
