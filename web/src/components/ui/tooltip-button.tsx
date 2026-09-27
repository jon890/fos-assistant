"use client";

import type { ComponentProps } from "react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

/**
 * 글자 없이 아이콘만 있는 단추다. 접근성 이름과 마우스를 올렸을 때의 풀이 글이 같은 `label` 에서 나온다.
 *
 * <p>`title` 속성은 쓰지 않는다. 풀이는 Tooltip 이 보인다. 화면 틀 맨 위의 `TooltipProvider` 안에서 쓴다.
 */
export function TooltipButton({ label, children, variant = "ghost", size = "icon-sm", ...props }:
  ComponentProps<typeof Button> & { label: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant={variant} size={size} aria-label={label} {...props}>{children}</Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
