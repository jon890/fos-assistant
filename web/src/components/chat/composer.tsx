"use client";

import { useLayoutEffect, useRef, useState, type ChangeEvent } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { describeError } from "../error-message";

type Props = {
  value: string;
  disabled: boolean;
  onChange(value: string): void;
  onSend(attachmentIds: number[]): void;
  /** 대화가 아직 없으면 null. 사진을 고르면 이 값이 없는 채로 첫 사진을 올릴 수 없다 */
  conversationId: number | null;
  agentCode: string;
  /** 이 에이전트의 대화에 사진을 붙일 수 있다. 거짓이면 사진 단추를 그리지 않는다 */
  acceptsAttachments: boolean;
  onConversationCreated(id: number): void;
};

const ACCEPTED_TYPES = ["image/jpeg", "image/png", "image/gif", "image/webp"];
const MAX_ATTACHMENTS = 10;
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
/** 미리보기의 긴 변 길이다. 열 장에 33MB 였던 실측이 있어 원본을 그대로 그리지 않는다 */
const THUMBNAIL_MAX_SIDE = 192;

type AttachmentItem = {
  key: string;
  previewUrl: string;
  status: "uploading" | "done" | "error";
  attachmentId: number | null;
  errorMessage: string | null;
};

async function buildThumbnail(file: File): Promise<string> {
  const bitmap = await createImageBitmap(file);
  const longSide = Math.max(bitmap.width, bitmap.height);
  const scale = Math.min(1, THUMBNAIL_MAX_SIDE / longSide);
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) {
    bitmap.close();
    throw new Error("캔버스를 만들지 못했다.");
  }
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();

  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (result) => (result ? resolve(result) : reject(new Error("미리보기를 만들지 못했다."))),
      "image/png",
    );
  });
  return URL.createObjectURL(blob);
}

export function Composer({
  value,
  disabled,
  onChange,
  onSend,
  conversationId,
  agentCode,
  acceptsAttachments,
  onConversationCreated,
}: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const [items, setItems] = useState<AttachmentItem[]>([]);
  const [pickNotice, setPickNotice] = useState<string | null>(null);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
  }, [value]);

  const uploading = items.some((item) => item.status === "uploading");
  const hasBlockingAttachment = items.some((item) => item.status !== "done");
  const sendDisabled = disabled || value.trim().length === 0 || hasBlockingAttachment;

  function updateItem(key: string, patch: Partial<AttachmentItem>) {
    setItems((previous) => previous.map((item) => (item.key === key ? { ...item, ...patch } : item)));
  }

  async function ensureConversationId(): Promise<number | null> {
    if (conversationId !== null) return conversationId;
    try {
      const response = await fetch("/api/chat/conversations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ agentCode }),
      });
      const payload = (await response.json()) as { conversationId?: number; code?: string; message?: string };
      if (!response.ok || !payload.conversationId) return null;
      onConversationCreated(payload.conversationId);
      return payload.conversationId;
    } catch {
      return null;
    }
  }

  async function uploadOne(file: File, targetConversationId: number) {
    const key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    let previewUrl = "";
    try {
      previewUrl = await buildThumbnail(file);
    } catch {
      previewUrl = "";
    }
    setItems((previous) => [
      ...previous,
      { key, previewUrl, status: "uploading", attachmentId: null, errorMessage: null },
    ]);

    try {
      const form = new FormData();
      form.append("file", file, file.name);
      const response = await fetch(`/api/chat/conversations/${targetConversationId}/attachments`, {
        method: "POST",
        body: form,
      });
      const payload = (await response.json()) as { id?: number; code?: string; message?: string };
      if (!response.ok || !payload.id) {
        updateItem(key, {
          status: "error",
          errorMessage: describeError(payload.code ?? "INTERNAL_ERROR", payload.message ?? "올리지 못했습니다."),
        });
        return;
      }
      updateItem(key, { status: "done", attachmentId: payload.id });
    } catch {
      updateItem(key, { status: "error", errorMessage: "올리지 못했습니다. 다시 시도해 주세요." });
    }
  }

  async function handleFiles(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length === 0) return;

    const accepted = files.filter((file) => ACCEPTED_TYPES.includes(file.type));
    const overflowCount = Math.max(0, accepted.length - MAX_ATTACHMENTS);
    const capped = accepted.slice(0, MAX_ATTACHMENTS);
    const oversize = capped.filter((file) => file.size > MAX_ATTACHMENT_BYTES);
    const toUpload = capped.filter((file) => file.size <= MAX_ATTACHMENT_BYTES);

    const notices: string[] = [];
    if (overflowCount > 0) {
      notices.push(`한 번에 ${MAX_ATTACHMENTS}장까지 올릴 수 있다. ${overflowCount}장은 올리지 않았다.`);
    }
    if (oversize.length > 0) {
      notices.push(`한 장은 10MB 까지 올릴 수 있다. ${oversize.length}장은 올리지 않았다.`);
    }
    setPickNotice(notices.length > 0 ? notices.join(" ") : null);

    if (toUpload.length === 0) return;

    const targetConversationId = await ensureConversationId();
    if (targetConversationId === null) {
      setPickNotice("대화를 시작하지 못했다. 잠시 뒤 다시 시도해 주세요.");
      return;
    }

    for (const file of toUpload) {
      void uploadOne(file, targetConversationId);
    }
  }

  function removeItem(key: string) {
    setItems((previous) => {
      const target = previous.find((item) => item.key === key);
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl);
      return previous.filter((item) => item.key !== key);
    });
  }

  function trySend() {
    if (sendDisabled) return;
    const attachmentIds = items
      .filter((item): item is AttachmentItem & { attachmentId: number } => item.status === "done" && item.attachmentId !== null)
      .map((item) => item.attachmentId);
    onSend(attachmentIds);
    for (const item of items) {
      if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
    }
    setItems([]);
    setPickNotice(null);
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        trySend();
      }}
      className="mx-auto w-full max-w-3xl pt-3"
    >
      {items.length > 0 ? (
        <div data-testid="attachment-previews" className="mb-2 flex items-center gap-2 overflow-x-auto pb-1">
          {items.map((item) => (
            <div key={item.key} className="relative shrink-0">
              {item.status === "error" ? (
                <div className="flex h-16 w-16 flex-col items-center justify-center gap-1 rounded-md border border-border bg-surface p-1 text-center text-[0.625rem] leading-tight text-muted">
                  <span>{item.errorMessage}</span>
                </div>
              ) : (
                <div className="relative h-16 w-16 overflow-hidden rounded-md border border-border bg-surface">
                  {item.previewUrl ? (
                    // eslint 설정이 없는 저장소라 next/image 대신 object URL 을 바로 그린다.
                    <img src={item.previewUrl} alt="" className="h-full w-full object-cover" />
                  ) : null}
                  {item.status === "uploading" ? (
                    <span
                      data-testid="attachment-uploading"
                      aria-hidden="true"
                      className="absolute inset-0 flex items-center justify-center bg-background/60"
                    >
                      <span className="h-4 w-4 animate-spin rounded-full border-2 border-border border-t-brand" />
                    </span>
                  ) : null}
                </div>
              )}
              <button
                type="button"
                aria-label="사진 지우기"
                onClick={() => removeItem(item.key)}
                className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full border border-border bg-background text-xs leading-none"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      ) : null}

      {pickNotice ? (
        <p data-testid="attachment-notice" className="mb-2 text-xs text-muted">
          {pickNotice}
        </p>
      ) : null}

      <div
        data-testid="composer-shell"
        className="flex items-end gap-2 rounded-3xl border border-border bg-background p-1.5 pl-4 focus-within:border-brand"
      >
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={() => {
            composing.current = false;
          }}
          onKeyDown={(event) => {
            if (
              event.key !== "Enter"
              || event.shiftKey
              || composing.current
              || event.nativeEvent.isComposing
            ) {
              return;
            }
            event.preventDefault();
            trySend();
          }}
          disabled={disabled}
          placeholder="무엇을 도와줄까요"
          className="max-h-[7.5rem] min-h-10 flex-1 resize-none overflow-y-auto bg-transparent py-2 text-base leading-6 outline-none disabled:opacity-50"
        />
        {acceptsAttachments ? (
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept={ACCEPTED_TYPES.join(",")}
              multiple
              hidden
              data-testid="attachment-input"
              onChange={(event) => void handleFiles(event)}
            />
            <IconButton
              label="사진 첨부"
              onClick={() => fileInputRef.current?.click()}
              disabled={disabled}
              className="h-10 w-10 shrink-0 rounded-full"
            >
              <svg
                viewBox="0 0 24 24"
                aria-hidden="true"
                className="h-5 w-5 fill-none stroke-current stroke-2"
              >
                <path
                  d="M4 8h3l1.5-2h7L17 8h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="12" cy="14" r="3.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </IconButton>
          </>
        ) : null}
        <Button
          type="submit"
          aria-label="보내기"
          disabled={sendDisabled}
          className="h-10 w-10 shrink-0 rounded-full !p-0"
        >
          {uploading ? (
            <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-on-brand/40 border-t-on-brand" />
          ) : (
            <svg
              viewBox="0 0 24 24"
              aria-hidden="true"
              className="h-5 w-5 fill-none stroke-current stroke-2"
            >
              <path
                d="M12 19V5m0 0-6 6m6-6 6 6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          )}
        </Button>
      </div>
    </form>
  );
}
