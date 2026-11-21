"use client";

import { FileText } from "lucide-react";
import { describeError } from "@/components/error-message";
import { artifactNames } from "@/lib/artifact-name";
import { attachmentPlaceholder } from "./variants";
import { ArtifactVaultStatus } from "./artifact/artifact-vault-status";
import type { MessageArtifact, MessageAttachment, Turn } from "./message-types";

export function AttachmentGallery({
  conversationId,
  attachments,
}: {
  conversationId: string | null;
  attachments: MessageAttachment[];
}) {
  if (attachments.length === 0 || conversationId === null) return null;
  return (
    <div className="mt-2 flex flex-wrap gap-2">
      {attachments.map((attachment) =>
        attachment.visible ? (
          <a
            key={attachment.id}
            href={`/api/chat/conversations/${conversationId}/attachments/${attachment.id}`}
            target="_blank"
            rel="noreferrer"
          >
            <img
              data-testid="message-attachment"
              src={`/api/chat/conversations/${conversationId}/attachments/${attachment.id}`}
              alt={attachment.originalName}
              loading="lazy"
              className="h-24 w-24 rounded-md border border-border object-cover"
            />
          </a>
        ) : (
          <div
            key={attachment.id}
            data-testid="message-attachment-gone"
            className={attachmentPlaceholder({ size: "message" })}
          >
            {describeError("ATTACHMENT_GONE", "사진을 표시할 수 없어요.")}
          </div>
        ),
      )}
    </div>
  );
}

export function ArtifactList({
  artifacts,
  conversationId,
  messageId,
  onOpen,
}: {
  artifacts: MessageArtifact[];
  conversationId: string | null;
  /** 저장한 답의 번호다. 아직 저장하지 않은 답이면 자동 보관 상태를 그리지 않는다 */
  messageId: Turn["id"];
  onOpen?(path: string, name: string): void;
}) {
  if (artifacts.length === 0) return null;
  const names = artifactNames(artifacts.map((artifact) => artifact.path));
  return (
    <ul className="mt-2 flex flex-col gap-1">
      {artifacts.map((artifact) => {
        const name = names.get(artifact.path) ?? artifact.path;
        const content = (
          <>
            <FileText aria-hidden="true" className="size-4 shrink-0" />
            <span className="min-w-0 truncate">{name}</span>
          </>
        );
        return (
          <li
            key={artifact.path}
            data-testid="message-artifact"
            className="flex min-w-0 items-center gap-2 text-sm"
          >
            {artifact.deleted || !onOpen ? (
              <span
                aria-disabled="true"
                className="flex min-w-0 items-center gap-2 text-muted-foreground opacity-60"
              >
                {content}
              </span>
            ) : (
              <button
                type="button"
                onClick={() => onOpen(artifact.path, name)}
                className="flex min-w-0 items-center gap-2 rounded-sm underline-offset-4 hover:underline"
              >
                {content}
              </button>
            )}
            {artifact.deleted ? (
              <span className="shrink-0 text-xs text-muted-foreground">
                보관 기간이 지나 볼 수 없어요.
              </span>
            ) : null}
            {conversationId !== null && typeof messageId === "number" ? (
              <ArtifactVaultStatus
                conversationId={conversationId}
                messageId={messageId}
                path={artifact.path}
                state={artifact.autoVault ?? null}
                error={artifact.autoVaultError ?? null}
              />
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
