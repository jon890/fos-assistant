import { ChevronLeft, ChevronRight } from "lucide-react";
import { TooltipButton } from "@/components/ui/tooltip-button";
import type { VersionSlot } from "@/lib/message-versions";

type Props = {
  slot: VersionSlot;
  onChange(index: number): void;
};

/** 같은 자리에 쌓인 메시지 판을 앞뒤로 넘긴다. */
export function VersionSwitcher({ slot, onChange }: Props) {
  if (slot.count <= 1) return null;

  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
      <TooltipButton label="이전 답" size="icon-xs" disabled={slot.index === 0} onClick={() => onChange(slot.index - 1)}>
        <ChevronLeft aria-hidden="true" />
      </TooltipButton>
      <span data-testid="version-label">{slot.index + 1}/{slot.count}</span>
      <TooltipButton label="다음 답" size="icon-xs" disabled={slot.index === slot.count - 1} onClick={() => onChange(slot.index + 1)}>
        <ChevronRight aria-hidden="true" />
      </TooltipButton>
    </span>
  );
}
