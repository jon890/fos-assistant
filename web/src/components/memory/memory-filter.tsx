"use client";

import { cn } from "cn";
import { isAgentMemory } from "@/lib/memory-source";
import type { Memory } from "./memory-list";

export type MemoryFilter = "ALL" | "USER" | "GROUP" | "AGENT";

export const MEMORY_FILTERS: {
  key: MemoryFilter;
  label: string;
  empty: string;
}[] = [
  { key: "ALL", label: "전체", empty: "아직 기억이 없어요." },
  {
    key: "USER",
    label: "나에 대해",
    empty: "아직 나에 대해 아는 기억이 없어요.",
  },
  {
    key: "GROUP",
    label: "그룹",
    empty: "아직 그룹이 함께 아는 기억이 없어요.",
  },
  {
    key: "AGENT",
    label: "에이전트가 남김",
    empty: "아직 에이전트가 남긴 기억이 없어요.",
  },
];

export function matchesFilter(memory: Memory, filter: MemoryFilter): boolean {
  if (filter === "USER" || filter === "GROUP") return memory.scope === filter;
  if (filter === "AGENT") return isAgentMemory(memory);
  return true;
}

/** 받아들인 기억을 범위나 남긴 쪽으로 거르는 단추 묶음이다. */
export function MemoryFilterBar({
  value,
  onChange,
}: {
  value: MemoryFilter;
  onChange(next: MemoryFilter): void;
}) {
  return (
    <div
      role="group"
      aria-label="기억 거르기"
      className="mb-3 flex flex-wrap gap-2"
    >
      {MEMORY_FILTERS.map((option) => (
        <button
          key={option.key}
          type="button"
          aria-pressed={value === option.key}
          onClick={() => onChange(option.key)}
          className={cn(
            "rounded-full border px-3 py-1 text-sm transition-colors duration-fast ease-out outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
            value === option.key
              ? "border-primary bg-primary text-primary-foreground"
              : "border-border text-muted-foreground hover:bg-muted",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
