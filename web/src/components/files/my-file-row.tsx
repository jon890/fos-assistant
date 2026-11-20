"use client";

import {
  Download,
  File,
  FileSpreadsheet,
  FileText,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { vaultContentUrl, type VaultItem } from "@/lib/artifact-vault-api";
import { displayName, KIND_LABELS, shortDate } from "@/lib/my-files";
import { formatSize } from "@/lib/workspace-file";
import { downloadUrl } from "@/components/files/my-file-preview";

/** 그림이 아닌 줄의 종류 표시다. 받는 형식이 아닌 옛 보관본은 일반 파일 아이콘이다. */
function KindMark({ kind }: { kind: VaultItem["kind"] }) {
  const Icon =
    kind === "TABLE" ? FileSpreadsheet : kind === "DOCUMENT" ? FileText : File;
  return (
    <span
      data-testid="my-file-kind"
      aria-label={kind ? KIND_LABELS[kind] : "파일"}
      role="img"
      className="flex size-10 shrink-0 items-center justify-center rounded border border-border bg-muted text-muted-foreground"
    >
      <Icon className="size-5" aria-hidden />
    </span>
  );
}

/**
 * 「내 파일」 목록의 한 줄이다(스펙 workspace REQ10, REQ13, REQ15, REQ18).
 *
 * <p>작은 그림이나 종류 표시, 제목, 설명, 나온 대화, 날짜, 크기와 「내려받기」, 「지우기」 를 보인다. 줄을 누르면 미리보기가 열린다.
 * 대화 이름을 누르면 그 대화의 파일만 남긴다. 지운 대화는 누를 수 없다. 좁은 폭에서는 두 단추가 아이콘만 남아 줄 밖으로 밀리지 않는다.
 */
export function MyFileRow({
  item,
  now,
  selected,
  onOpen,
  onConversation,
  onDelete,
}: {
  item: VaultItem;
  now: Date;
  selected: boolean;
  onOpen(): void;
  onConversation(id: string, title: string): void;
  onDelete(): void;
}) {
  const name = displayName(item.path, item.title);
  const conversationId = item.conversationId ?? null;
  const conversationTitle = item.conversationTitle ?? null;
  return (
    <li
      data-testid="my-file"
      aria-current={selected ? "true" : undefined}
      className={`flex min-w-0 items-center gap-3 rounded-md border p-2 ${selected ? "border-primary" : "border-border"}`}
    >
      <button
        type="button"
        onClick={onOpen}
        className="flex min-w-0 flex-1 items-center gap-3 text-left"
        aria-label={`${name} 미리보기`}
      >
        {item.kind === "IMAGE" ? (
          // eslint-disable-next-line @next/next/no-img-element -- 보관본 주소는 next/image 최적화를 거치지 않는다
          <img
            src={vaultContentUrl(item.id)}
            alt=""
            loading="lazy"
            data-testid="my-file-thumbnail"
            className="size-10 shrink-0 rounded border border-border object-cover"
          />
        ) : (
          <KindMark kind={item.kind} />
        )}
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium" title={item.path}>
            {name}
          </span>
          {item.description ? (
            <span
              data-testid="my-file-description"
              className="block truncate text-xs text-muted-foreground"
            >
              {item.description}
            </span>
          ) : null}
        </span>
      </button>
      <div className="flex min-w-0 shrink-0 flex-col items-end gap-1 text-xs text-muted-foreground sm:w-56">
        {conversationId && conversationTitle !== null ? (
          <button
            type="button"
            data-testid="my-file-conversation"
            onClick={() => onConversation(conversationId, conversationTitle)}
            className="max-w-28 truncate underline-offset-4 hover:underline sm:max-w-full"
            title={conversationTitle}
          >
            {conversationTitle}
          </button>
        ) : (
          <span data-testid="my-file-conversation">지운 대화</span>
        )}
        <span>
          {shortDate(item.vaultedAt, now)} · {formatSize(item.byteSize)}
        </span>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant="ghost" size="icon-sm" asChild>
          <a
            href={downloadUrl(item)}
            aria-label={`${name} 내려받기`}
            title="내려받기"
          >
            <Download aria-hidden />
          </a>
        </Button>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`${name} 지우기`}
          title="지우기"
          onClick={onDelete}
        >
          <Trash2 aria-hidden />
        </Button>
      </div>
    </li>
  );
}
