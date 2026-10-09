"use client";

import { useEffect } from "react";

const LEAVE_MESSAGE =
  "아직 보내지 못한 사진이 있어요. 페이지를 떠나면 이 메시지가 사라져요. 떠날까요?";

/** 아직 보내지 못한 사진이 있으면 페이지를 떠나기 전에 확인한다. */
export function useOutgoingNavigation(active: boolean) {
  useEffect(() => {
    if (!active) return;
    const currentUrl = window.location.href;
    const currentState = window.history.state;
    const confirmLeave = () => window.confirm(LEAVE_MESSAGE);
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    const onClick = (event: MouseEvent) => {
      if (
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      )
        return;
      const anchor =
        event.target instanceof Element ? event.target.closest("a") : null;
      if (
        !anchor ||
        anchor.target === "_blank" ||
        anchor.hasAttribute("download") ||
        anchor.href === window.location.href
      )
        return;
      if (!confirmLeave()) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    const onPopState = (event: PopStateEvent) => {
      if (window.location.href === currentUrl || confirmLeave()) return;
      // Next.js가 이동한 화면을 그리기 전에 멈추고, 보고 있던 대화의 이력 상태와 주소를 되돌린다.
      event.stopImmediatePropagation();
      window.history.pushState(currentState, "", currentUrl);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (
        (event.ctrlKey || event.metaKey) &&
        event.shiftKey &&
        event.key.toLowerCase() === "o" &&
        !confirmLeave()
      ) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    window.addEventListener("beforeunload", beforeUnload);
    document.addEventListener("click", onClick, true);
    window.addEventListener("popstate", onPopState, true);
    window.addEventListener("keydown", onKeyDown, true);
    return () => {
      window.removeEventListener("beforeunload", beforeUnload);
      document.removeEventListener("click", onClick, true);
      window.removeEventListener("popstate", onPopState, true);
      window.removeEventListener("keydown", onKeyDown, true);
    };
  }, [active]);
}
