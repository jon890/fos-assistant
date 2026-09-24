"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export function useShortcuts(enabled: boolean, onNew: () => void, onToggleSidebar: () => void, onSearch: () => void) {
  const router = useRouter();
  useEffect(() => {
    if (!enabled) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.isComposing || event.defaultPrevented || !(event.ctrlKey || event.metaKey)) return;
      const key = event.key.toLowerCase();
      if (event.shiftKey && key === "o") {
        event.preventDefault();
        onNew();
        router.push("/");
      } else if (event.shiftKey && key === "s") {
        event.preventDefault();
        onToggleSidebar();
      } else if (!event.shiftKey && key === "k") {
        event.preventDefault();
        onSearch();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [enabled, onNew, onToggleSidebar, onSearch, router]);
}
