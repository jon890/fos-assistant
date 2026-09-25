"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState, type ChangeEvent } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import type { AgentView } from "@/lib/agent";
import { describeError } from "../error-message";
import { AgentMention, filterAgents, findMention, mentionOptionId } from "./agent-mention";

type Props = {
  value: string;
  disabled: boolean;
  onChange(value: string): void;
  /** 전송이 실제로 끝났는지를 돌려준다. 실패하면 미리보기를 지우지 않는다 */
  onSend(attachmentIds: number[]): Promise<boolean>;
  /** 대화가 아직 없으면 null. 사진을 고르면 이 값이 없는 채로 첫 사진을 올릴 수 없다 */
  conversationId: number | null;
  agentCode: string;
  /** 이 에이전트의 대화에 사진을 붙일 수 있다. 거짓이면 사진 단추를 그리지 않는다 */
  acceptsAttachments: boolean;
  onConversationCreated(id: number): void;
  /** 답을 만드는 중이다. 참이면 보내기 자리에서 중지를 보인다. */
  running: boolean;
  /** `started` 사건 뒤, 아직 중지를 누르지 않았을 때 참이다. */
  canStop: boolean;
  onStop(): void;
  /**
   * 입력칸에 `@` 를 치면 에이전트를 고르는 목록을 띄운다. 새 대화에서만 준다.
   * 대화의 에이전트는 첫 메시지가 정하고 그 뒤로 바뀌지 않는다.
   */
  mention?: { agents: AgentView[]; onPick(code: string): void };
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
  /** 이 첨부가 올라간 대화 번호다. 지울 때 이 번호로 서버 DELETE 를 부른다 */
  conversationId: number;
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
  running,
  canStop,
  onStop,
  mention,
}: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const [items, setItems] = useState<AttachmentItem[]>([]);
  const [pickNotice, setPickNotice] = useState<string | null>(null);
  /** 빈 대화를 만드는 요청이 진행 중이면 그 Promise 를 담아 다시 쓴다. 연달아 고르면 두 번 도는 것을 막는다 */
  const creatingConversationRef = useRef<Promise<number | null> | null>(null);
  /** 올리는 중에 지운 첨부의 key 다. 올리기 응답을 받으면 그때 서버 DELETE 를 부른다 */
  const pendingRemovalRef = useRef<Set<string>>(new Set());
  /**
   * 이 Composer 가 화면에 붙어 있는지다. 대화를 바꾸면 부모가 Composer 를 새로 만든다. 그 뒤에 끝난
   * 요청이 부모의 선택을 바꾸거나 사라진 목록에 첨부를 더하지 못하게 이 값으로 막는다.
   */
  const mountedRef = useRef(false);
  /** 정리할 때 읽는 최신 목록이다. unmount 정리는 마지막 렌더의 `items` 를 볼 수 없어 따로 둔다 */
  const itemsRef = useRef<AttachmentItem[]>([]);
  /** 전송 요청에 실은 첨부다. 응답을 기다리는 동안에는 메시지에 묶일 수 있어 정리에서 지우지 않는다 */
  const sendingItemsRef = useRef<AttachmentItem[]>([]);
  const mentionListId = useId();
  /** 입력칸의 커서 자리다. `@` 목록은 커서 앞의 글만 본다 */
  const [caret, setCaret] = useState(0);
  /** `Esc` 로 닫은 `@` 의 자리다. 같은 `@` 뒤에 글을 더 쳐도 다시 띄우지 않는다 */
  const [dismissedMentionStart, setDismissedMentionStart] = useState<number | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  /** 고른 뒤 `@` 부터 커서까지를 뺀 글이 그려지면 커서를 이 자리에 둔다 */
  const pendingCaretRef = useRef<number | null>(null);

  useLayoutEffect(() => {
    itemsRef.current = items;
  }, [items]);

  useEffect(() => {
    mountedRef.current = true;
    const pendingRemoval = pendingRemovalRef.current;
    return () => {
      mountedRef.current = false;
      // 대화를 바꿔 이 Composer 가 사라진다. 메시지에 묶이지 않은 첨부가 남으면 원래 대화의 상한을
      // 보관 기간 내내 차지하므로 여기서 지운다. 올리는 중인 것은 `uploadOne` 이 응답을 받은 뒤 지운다.
      const sending = new Set(sendingItemsRef.current.map((item) => item.key));
      for (const item of itemsRef.current) {
        if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
        if (item.status === "done" && item.attachmentId !== null && !sending.has(item.key)) {
          void deleteAttachment(item.conversationId, item.attachmentId);
        }
      }
      itemsRef.current = [];
      pendingRemoval.clear();
    };
  }, []);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
  }, [value]);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    const nextCaret = pendingCaretRef.current;
    if (!textarea || nextCaret === null) return;
    pendingCaretRef.current = null;
    textarea.focus();
    textarea.setSelectionRange(nextCaret, nextCaret);
  }, [value]);

  const found = mention ? findMention(value, caret) : null;
  const openMention = found !== null && found.start !== dismissedMentionStart ? found : null;
  const mentionMatches = mention && openMention ? filterAgents(mention.agents, openMention.query) : [];
  const activeMentionIndex = Math.min(mentionIndex, Math.max(0, mentionMatches.length - 1));

  function changeValue(nextValue: string, nextCaret: number) {
    const next = mention ? findMention(nextValue, nextCaret) : null;
    if (next === null || next.start !== dismissedMentionStart) setDismissedMentionStart(null);
    setCaret(nextCaret);
    setMentionIndex(0);
    onChange(nextValue);
  }

  function pickMention(code: string) {
    if (!mention || !openMention) return;
    const end = textareaRef.current?.selectionStart ?? caret;
    pendingCaretRef.current = openMention.start;
    mention.onPick(code);
    changeValue(value.slice(0, openMention.start) + value.slice(end), openMention.start);
  }

  const uploading = items.some((item) => item.status === "uploading");
  const hasBlockingAttachment = items.some((item) => item.status !== "done");
  const sendDisabled = disabled || value.trim().length === 0 || hasBlockingAttachment;

  function updateItem(key: string, patch: Partial<AttachmentItem>) {
    setItems((previous) => previous.map((item) => (item.key === key ? { ...item, ...patch } : item)));
  }

  async function ensureConversationId(): Promise<number | null> {
    if (conversationId !== null) return conversationId;
    if (creatingConversationRef.current) return creatingConversationRef.current;

    const promise = (async () => {
      try {
        const response = await fetch("/api/chat/conversations", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ agentCode }),
        });
        const payload = (await response.json()) as { conversationId?: number; code?: string; message?: string };
        if (!response.ok || !payload.conversationId) {
          setPickNotice("대화를 시작하지 못했다. 잠시 뒤 다시 시도해 주세요.");
          return null;
        }
        // 요청 도중 대화를 바꿨으면 부모는 이미 다른 대화를 보고 있다. 그 선택을 덮지 않는다.
        if (!mountedRef.current) return null;
        onConversationCreated(payload.conversationId);
        return payload.conversationId;
      } catch {
        setPickNotice("대화를 시작하지 못했다. 잠시 뒤 다시 시도해 주세요.");
        return null;
      } finally {
        creatingConversationRef.current = null;
      }
    })();
    creatingConversationRef.current = promise;
    return promise;
  }

  /** 지우는 단추가 눌린 첨부다. 업로드 응답이 오면 DELETE 로 마무리한다 */
  function finalizeRemovalIfRequested(key: string, targetConversationId: number, attachmentId: number | null) {
    if (!pendingRemovalRef.current.has(key)) return false;
    pendingRemovalRef.current.delete(key);
    if (attachmentId !== null) void deleteAttachment(targetConversationId, attachmentId);
    return true;
  }

  async function deleteAttachment(targetConversationId: number, attachmentId: number) {
    try {
      await fetch(`/api/chat/conversations/${targetConversationId}/attachments/${attachmentId}`, {
        method: "DELETE",
      });
    } catch {
      // 지우기 요청이 실패해도 화면은 이미 그 미리보기를 치웠다. 사용자가 다시 시도할 자리가 없어 조용히 넘어간다.
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
    if (!mountedRef.current) {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      return;
    }
    setItems((previous) => [
      ...previous,
      { key, previewUrl, status: "uploading", attachmentId: null, errorMessage: null, conversationId: targetConversationId },
    ]);

    try {
      const form = new FormData();
      form.append("file", file, file.name);
      const response = await fetch(`/api/chat/conversations/${targetConversationId}/attachments`, {
        method: "POST",
        body: form,
      });
      const payload = (await response.json()) as { id?: number; code?: string; message?: string };
      if (!mountedRef.current) {
        // 올리는 동안 대화를 바꿨다. 이 첨부를 보낼 자리가 사라졌으므로 서버에서도 지운다.
        if (response.ok && payload.id) void deleteAttachment(targetConversationId, payload.id);
        return;
      }
      if (!response.ok || !payload.id) {
        finalizeRemovalIfRequested(key, targetConversationId, null);
        updateItem(key, {
          status: "error",
          errorMessage: describeError(payload.code ?? "INTERNAL_ERROR", payload.message ?? "올리지 못했습니다."),
        });
        return;
      }
      if (finalizeRemovalIfRequested(key, targetConversationId, payload.id)) return;
      updateItem(key, { status: "done", attachmentId: payload.id });
    } catch {
      finalizeRemovalIfRequested(key, targetConversationId, null);
      updateItem(key, { status: "error", errorMessage: "올리지 못했습니다. 다시 시도해 주세요." });
    }
  }

  async function handleFiles(event: ChangeEvent<HTMLInputElement>) {
    if (running) return;
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length === 0) return;

    const rejectedFormatCount = files.filter((file) => !ACCEPTED_TYPES.includes(file.type)).length;
    const accepted = files.filter((file) => ACCEPTED_TYPES.includes(file.type));
    // 상한은 한 번에 고를 때만 센다. 이미 붙은 첨부를 빼고 남은 자리만큼만 올린다.
    const remainingSlots = Math.max(0, MAX_ATTACHMENTS - items.length);
    const overflowCount = Math.max(0, accepted.length - remainingSlots);
    const capped = accepted.slice(0, remainingSlots);
    const oversize = capped.filter((file) => file.size > MAX_ATTACHMENT_BYTES);
    const toUpload = capped.filter((file) => file.size <= MAX_ATTACHMENT_BYTES);

    const notices: string[] = [];
    if (rejectedFormatCount > 0) {
      notices.push(`이미지 파일만 올릴 수 있다. ${rejectedFormatCount}장은 올리지 않았다.`);
    }
    if (overflowCount > 0) {
      notices.push(`한 번에 ${MAX_ATTACHMENTS}장까지 올릴 수 있다. ${overflowCount}장은 올리지 않았다.`);
    }
    if (oversize.length > 0) {
      notices.push(`한 장은 10MB 까지 올릴 수 있다. ${oversize.length}장은 올리지 않았다.`);
    }
    setPickNotice(notices.length > 0 ? notices.join(" ") : null);

    if (toUpload.length === 0) return;

    const targetConversationId = await ensureConversationId();
    if (targetConversationId === null || !mountedRef.current) return;

    for (const file of toUpload) {
      void uploadOne(file, targetConversationId);
    }
  }

  function removeItem(key: string) {
    if (running) return;
    // 부수 효과는 updater 밖에서 한 번만 부른다. 개발 모드의 StrictMode 는 updater 를 두 번 돌린다.
    const target = itemsRef.current.find((item) => item.key === key);
    if (!target) return;
    if (target.previewUrl) URL.revokeObjectURL(target.previewUrl);
    if (target.status === "uploading") {
      pendingRemovalRef.current.add(key);
    } else if (target.status === "done" && target.attachmentId !== null) {
      void deleteAttachment(target.conversationId, target.attachmentId);
    }
    itemsRef.current = itemsRef.current.filter((item) => item.key !== key);
    setItems((previous) => previous.filter((item) => item.key !== key));
  }

  async function trySend() {
    if (sendDisabled) return;
    const sendingItems = items.filter(
      (item): item is AttachmentItem & { attachmentId: number } => item.status === "done" && item.attachmentId !== null,
    );
    sendingItemsRef.current = sendingItems;
    let succeeded = false;
    try {
      succeeded = await onSend(sendingItems.map((item) => item.attachmentId));
    } finally {
      sendingItemsRef.current = [];
    }
    if (!mountedRef.current) {
      // 기다리는 동안 이 Composer 가 사라졌다. 실패로 끝나도 지우지 않는다. 서버가 메시지를 저장하고 첨부를
      // 묶은 뒤에 스트림만 끊긴 경우도 실패로 오므로, 지우면 보낸 사진이 사라질 수 있다. 묶이지 않았다면
      // 보관 기간이 지나 정리된다.
      for (const item of sendingItems) {
        if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
      }
      return;
    }
    if (!succeeded) return;
    for (const item of items) {
      if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
    }
    // 보낸 첨부는 메시지에 묶였다. 이 뒤에 unmount 정리가 돌아도 지우지 않게 ref 를 먼저 비운다.
    itemsRef.current = [];
    setItems([]);
    setPickNotice(null);
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void trySend();
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
                // 보내는 동안 지우면 막 메시지에 묶인 첨부에 DELETE 가 간다.
                disabled={disabled || running}
                className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full border border-border bg-background text-xs leading-none disabled:opacity-50"
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
        className="relative flex items-end gap-2 rounded-3xl border border-border bg-background p-1.5 pl-4 focus-within:border-brand"
      >
        {mention && openMention ? (
          <AgentMention
            id={mentionListId}
            agents={mention.agents}
            query={openMention.query}
            activeIndex={activeMentionIndex}
            onPick={pickMention}
          />
        ) : null}
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          aria-label="메시지"
          aria-controls={openMention ? mentionListId : undefined}
          aria-activedescendant={openMention && mentionMatches.length > 0
            ? mentionOptionId(mentionListId, activeMentionIndex) : undefined}
          onChange={(event) => changeValue(event.target.value, event.target.selectionStart)}
          onSelect={(event) => setCaret(event.currentTarget.selectionStart)}
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={() => {
            composing.current = false;
          }}
          onKeyDown={(event) => {
            const imeComposing = composing.current || event.nativeEvent.isComposing;
            if (openMention && event.key === "Escape") {
              // 대화 화면의 Esc 처리기가 이 사건을 건너뛰게 한다. 목록만 닫고 중지나 패널 닫기로 넘기지 않는다.
              // 한글 조합 중에도 같다. 조합 중이라고 넘기면 목록이 떠 있는데 패널이 닫힌다.
              event.preventDefault();
              event.stopPropagation();
              setDismissedMentionStart(openMention.start);
              return;
            }
            if (openMention && !imeComposing) {
              if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault();
                if (mentionMatches.length > 0) {
                  const step = event.key === "ArrowDown" ? 1 : -1;
                  setMentionIndex((activeMentionIndex + step + mentionMatches.length) % mentionMatches.length);
                }
                return;
              }
              if (event.key === "Enter" || (event.key === "Tab" && mentionMatches.length > 0)) {
                // 목록이 떠 있는 동안의 Enter 는 고르기다. 맞는 것이 없어도 보내지 않는다.
                event.preventDefault();
                const picked = mentionMatches[activeMentionIndex];
                if (picked) pickMention(picked.code);
                return;
              }
            }
            if (
              event.key !== "Enter"
              || event.shiftKey
              || composing.current
              || event.nativeEvent.isComposing
              || running
            ) {
              return;
            }
            event.preventDefault();
            void trySend();
          }}
          disabled={disabled}
          placeholder={mention ? "@ 로 에이전트를 부른다" : "무엇을 도와줄까요"}
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
              disabled={disabled || running}
              data-testid="attachment-input"
              onChange={(event) => void handleFiles(event)}
            />
            <IconButton
              label="사진 첨부"
              onClick={() => fileInputRef.current?.click()}
              disabled={disabled || running}
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
        {running ? (
          <Button
            type="button"
            aria-label="중지"
            disabled={!canStop}
            onClick={onStop}
            className="h-10 w-10 shrink-0 rounded-full !p-0"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" className="h-5 w-5 fill-current">
              <rect x="7" y="7" width="10" height="10" rx="1" />
            </svg>
          </Button>
        ) : (
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
        )}
      </div>
    </form>
  );
}
