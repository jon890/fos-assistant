"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/** Fullscreen API 가 없거나 거절되면 같은 화면을 고정 오버레이로 넓힌다. */
export function useScreenFullscreen() {
  const ref = useRef<HTMLElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [expanded, setExpanded] = useState(false);

  const closeOverlay = useCallback(() => {
    if (dialogRef.current?.matches(":modal")) {
      dialogRef.current.close();
      dialogRef.current.show();
    }
    setExpanded(false);
    triggerRef.current?.focus();
  }, []);

  async function toggle() {
    if (expanded) {
      if (document.fullscreenElement === ref.current)
        await document.exitFullscreen();
      closeOverlay();
      return;
    }
    try {
      await ref.current?.requestFullscreen();
    } catch {
      // dialog 의 최상위 레이어는 부모의 transform 과 스크롤 영역에 잘리지 않는다.
      dialogRef.current?.close();
      dialogRef.current?.showModal();
    }
    setExpanded(true);
    triggerRef.current?.focus();
  }

  useEffect(() => {
    const change = () => {
      if (!document.fullscreenElement) {
        closeOverlay();
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("fullscreenchange", change);
    return () => document.removeEventListener("fullscreenchange", change);
  }, [closeOverlay]);

  useEffect(() => {
    const section = ref.current;
    if (!expanded || !section) return;
    const previous = document.activeElement;
    const siblings: { element: HTMLElement; inert: boolean }[] = [];
    let branch: HTMLElement = section;
    while (branch.parentElement) {
      for (const sibling of branch.parentElement.children) {
        if (sibling !== branch && sibling instanceof HTMLElement) {
          siblings.push({ element: sibling, inert: sibling.inert });
          sibling.inert = true;
        }
      }
      branch = branch.parentElement;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !document.fullscreenElement) {
        event.preventDefault();
        closeOverlay();
      }
      if (event.key !== "Tab") return;
      const focusable = Array.from(
        section.querySelectorAll<HTMLElement>(
          "button:not(:disabled), input:not(:disabled), select:not(:disabled)",
        ),
      );
      const first = focusable[0];
      const last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    };
    section.addEventListener("keydown", onKey);
    return () => {
      section.removeEventListener("keydown", onKey);
      siblings.forEach(({ element, inert }) => {
        element.inert = inert;
      });
      if (previous instanceof HTMLElement && previous.isConnected)
        previous.focus();
    };
  }, [expanded, closeOverlay]);

  return {
    screenRef: ref,
    dialogRef,
    triggerRef,
    expanded,
    toggle,
    closeOverlay,
  };
}
