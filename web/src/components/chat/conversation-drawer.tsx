"use client";

import { useEffect, type ReactNode } from "react";

type Props = {
  open: boolean;
  onClose(): void;
  children: ReactNode;
};

export function ConversationDrawer({ open, onClose, children }: Props) {
  useEffect(() => {
    if (!open) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [open, onClose]);

  return (
    <>
      <button
        type="button"
        aria-label="대화 목록 닫기"
        onClick={onClose}
        tabIndex={open ? 0 : -1}
        className={`fixed inset-0 z-30 bg-foreground/35 transition-opacity md:hidden ${
          open ? "opacity-100" : "pointer-events-none opacity-0"
        }`}
      />
      <aside
        aria-label="대화 목록"
        className={`fixed inset-y-0 left-0 z-40 w-72 border-r border-border bg-background p-4 transition-transform md:static md:z-auto md:w-64 md:translate-x-0 md:p-0 md:pr-4 ${
          open ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        {children}
      </aside>
    </>
  );
}
