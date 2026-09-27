"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";

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
    // 코드 블록 위에도 놓이므로 바탕을 채워 글자가 코드와 겹쳐 보이지 않게 한다.
    <Button variant="outline" size="xs" aria-label={label} onClick={() => void copy()}
      className="bg-background text-muted-foreground">
      <span aria-live="polite">{notice ?? label}</span>
    </Button>
  );
}
