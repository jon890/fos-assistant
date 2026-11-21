import { Reply, RotateCcw } from "lucide-react";
import { CopyButton } from "@/components/ui/copy-button";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { VersionSwitcher } from "./version-switcher";
import type { VersionSlot } from "@/lib/message-versions";

export function MessageActions({
  content,
  latest,
  version,
  onVersionChange,
  canRegenerate,
  onRegenerate,
  onReply,
  copyLabel = "답 복사",
}: {
  content: string;
  latest: boolean;
  version?: VersionSlot;
  onVersionChange?(index: number): void;
  canRegenerate?: boolean;
  onRegenerate?(): void;
  /** 이 메시지를 집어 입력창 위에 둔다(스펙 chat-reference REQ2) */
  onReply?(): void;
  copyLabel?: string;
}) {
  return (
    // 한 번만 쓰는 배치다. 넓은 폭에서 마지막 답이 아니면 마우스를 올리거나 초점이 들어올 때만 보인다.
    <div
      className={`mt-2 flex ${latest ? "md:opacity-100" : "md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100"}`}
    >
      {version && onVersionChange ? (
        <VersionSwitcher slot={version} onChange={onVersionChange} />
      ) : null}
      <CopyButton text={content} label={copyLabel} />
      {canRegenerate && onRegenerate ? (
        <TooltipButton
          variant="outline"
          size="icon-xs"
          label="답 다시 만들기"
          onClick={onRegenerate}
          className="ml-2 bg-background text-muted-foreground"
        >
          <RotateCcw aria-hidden="true" />
        </TooltipButton>
      ) : null}
      {/* 「답 다시 만들기」 뒤에 둔다. 복사에서 Tab 으로 다시 만들기에 바로 가던 순서를 지킨다. */}
      {onReply ? (
        <TooltipButton
          variant="outline"
          size="icon-xs"
          label="답장"
          onClick={onReply}
          className="ml-2 bg-background text-muted-foreground"
        >
          <Reply aria-hidden="true" />
        </TooltipButton>
      ) : null}
    </div>
  );
}
