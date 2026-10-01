"use client";

import { usePathname } from "next/navigation";
import { ViewTransition } from "react";
import { screenKey, viewTransitionEnabled } from "./screen-key";

const ENABLED = viewTransitionEnabled(process.env.NEXT_PUBLIC_VIEW_TRANSITION);

/**
 * 화면을 옮길 때 들어오는 본문에 움직임을 준다.
 * 대화 화면은 감싸지 않는다. 첫 메시지를 보내 주소가 바뀌어도 같은 화면이 살아 있어야 흐르던 답이 남고,
 * 대화를 오갈 때 입력창이 깜빡이지 않는다.
 */
export function ScreenTransition({ children }: { children: React.ReactNode }) {
  const key = screenKey(usePathname());
  if (key === "chat") return children;
  if (ENABLED) {
    return (
      <ViewTransition key={key} enter="screen" exit="screen" default="none">
        {children}
      </ViewTransition>
    );
  }
  // 화면 전환을 끈 빌드에서는 들어오는 화면만 CSS 로 움직인다.
  return (
    <div key={key} className="animate-screen-in">
      {children}
    </div>
  );
}
