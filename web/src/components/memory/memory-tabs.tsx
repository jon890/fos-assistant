"use client";

import { cn } from "cn";

export type MemoryTab = "memory" | "document";

const TABS: [MemoryTab, string][] = [
  ["memory", "기억"],
  ["document", "문서"],
];

/** 「기억」 과 「문서」 탭이다. 판의 id 는 `memory-panel-<탭>` 이다. */
export function MemoryTabs({
  value,
  onChange,
}: {
  value: MemoryTab;
  onChange(next: MemoryTab): void;
}) {
  return (
    <div
      role="tablist"
      aria-label="기억과 문서"
      className="mb-6 flex gap-1 border-b border-border"
    >
      {TABS.map(([key, label]) => (
        <button
          key={key}
          type="button"
          role="tab"
          id={`memory-tab-${key}`}
          aria-selected={value === key}
          aria-controls={`memory-panel-${key}`}
          onClick={() => onChange(key)}
          className={cn(
            "-mb-px border-b-2 px-4 py-2 text-sm font-medium transition-colors duration-fast ease-out outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
            value === key
              ? "border-primary text-foreground"
              : "border-transparent text-muted-foreground hover:text-foreground",
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
