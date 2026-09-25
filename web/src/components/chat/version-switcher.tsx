import type { VersionSlot } from "@/lib/message-versions";

type Props = {
  slot: VersionSlot;
  onChange(index: number): void;
};

/** 같은 자리에 쌓인 메시지 판을 앞뒤로 넘긴다. */
export function VersionSwitcher({ slot, onChange }: Props) {
  if (slot.count <= 1) return null;

  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted">
      <button
        type="button"
        aria-label="이전 판"
        disabled={slot.index === 0}
        onClick={() => onChange(slot.index - 1)}
        className="rounded px-1.5 py-1 hover:bg-surface-raised disabled:opacity-40"
      >
        ‹
      </button>
      <span data-testid="version-label">{slot.index + 1}/{slot.count}</span>
      <button
        type="button"
        aria-label="다음 판"
        disabled={slot.index === slot.count - 1}
        onClick={() => onChange(slot.index + 1)}
        className="rounded px-1.5 py-1 hover:bg-surface-raised disabled:opacity-40"
      >
        ›
      </button>
    </span>
  );
}
