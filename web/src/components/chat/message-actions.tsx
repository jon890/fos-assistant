import { CopyButton } from "@/components/ui/copy-button";
import { VersionSwitcher } from "./version-switcher";
import type { VersionSlot } from "@/lib/message-versions";

export function MessageActions({ content, latest, version, onVersionChange, canRegenerate, onRegenerate }: {
  content: string; latest: boolean; version?: VersionSlot; onVersionChange?(index: number): void;
  canRegenerate?: boolean; onRegenerate?(): void;
}) {
  return (
    <div className={`mt-2 flex ${latest ? "md:opacity-100" : "md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100"}`}>
      {version && onVersionChange ? <VersionSwitcher slot={version} onChange={onVersionChange} /> : null}
      <CopyButton text={content} label="답 복사" />
      {canRegenerate && onRegenerate ? <button type="button" aria-label="다시 생성" onClick={onRegenerate}
        className="ml-2 rounded-md border border-border px-2 py-1 text-xs hover:bg-surface-raised">다시 생성</button> : null}
    </div>
  );
}
