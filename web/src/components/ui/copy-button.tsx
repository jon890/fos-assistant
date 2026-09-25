"use client";

import { useEffect, useState } from "react";

export function CopyButton({ text, label }: { text: string; label: string }) {
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 2_000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setNotice("복사됨");
    } catch {
      setNotice("복사하지 못했다");
    }
  }

  return (
    <button type="button" aria-label={label} onClick={() => void copy()}
      className="rounded-md border border-border bg-background px-2 py-1 text-xs text-muted hover:bg-surface-raised">
      <span aria-live="polite">{notice ?? label}</span>
    </button>
  );
}
