"use client";

import { X } from "lucide-react";
import { AgentBadge } from "@/components/agent/agent-badge";
import { TooltipButton } from "@/components/ui/tooltip-button";
import type { AgentView } from "@/lib/agent";

/**
 * 대화 중 `@` 로 고른 에이전트를 입력창 위에 보인다. 보내면 이 에이전트가 바로 맡는다
 * (docs/features/agent-team/design.md 의 「PR4 @ 멘션」).
 */
export function DelegateChip({
  agent,
  onClear,
  disabled,
}: {
  agent: AgentView;
  onClear(): void;
  disabled: boolean;
}) {
  return (
    <div
      data-testid="delegate-chip"
      className="mb-2 flex w-fit max-w-full items-center gap-2 rounded-full border border-border bg-card py-1 pl-1 pr-1.5 text-sm"
    >
      <AgentBadge agent={agent} size="sm" />
      <span className="min-w-0 truncate">{agent.name}에게 맡기기</span>
      <TooltipButton
        label="맡기기 취소"
        variant="ghost"
        size="icon-xs"
        onClick={onClear}
        disabled={disabled}
        className="rounded-full"
      >
        <X aria-hidden="true" />
      </TooltipButton>
    </div>
  );
}
