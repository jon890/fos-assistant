"use client";

import { describePendingFailure } from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationSend } from "./use-conversation-send";
import type { MessageAttachment } from "./message-types";

type Context = Pick<
  ConversationSessionState,
  | "conversationIdRef"
  | "enqueueing"
  | "selectionVersion"
  | "pending"
  | "setError"
  | "setDraft"
  | "sending"
  | "draft"
  | "setPendingBusy"
> &
  Pick<ConversationSend, "send">;

export function useConversationQueue({
  conversationIdRef,
  enqueueing,
  selectionVersion,
  pending,
  setError,
  setDraft,
  sending,
  draft,
  setPendingBusy,
  send,
}: Context) {
  /**
   * 글을 대기 메시지로 더한다. 더했는지를 돌려준다.
   *
   * <p>대기 줄이 멈춰 있었으면 이어서 푼다. 멈춰 둔 글과 새 글이 순서대로 합쳐져 간다. 실패하면 입력창의 글을
   * 그대로 두고 까닭을 알린다.
   *
   * @param fromDraft 입력창의 글을 보냈다. 더한 뒤 입력창을 비운다. 질문 카드의 답은 입력창과 무관하다.
   */
  async function queueMessage(
    text: string,
    fromDraft: boolean,
  ): Promise<boolean> {
    // 새 대화의 첫 turn 은 `started` 가 대화 식별자를 실어 올 때까지 대기 메시지를 받을 대화가 없다.
    if (
      text.length === 0 ||
      conversationIdRef.current === null ||
      enqueueing.current
    )
      return false;
    const version = selectionVersion.current;
    const wasHeld = pending.queue.held;
    enqueueing.current = true;
    setError(null);
    try {
      const result = await pending.enqueue(text);
      if (selectionVersion.current !== version) return result.ok;
      if (!result.ok) {
        setError(
          describePendingFailure(
            result,
            "메시지를 보내지 못했어요. 잠시 뒤 다시 보내 주세요.",
          ),
        );
        return false;
      }
      // 기다리는 동안 다음 글을 쓰기 시작했으면 그 글은 지우지 않는다.
      if (fromDraft)
        setDraft((current) => (current.trim() === text ? "" : current));
      if (wasHeld) {
        const released = await pending.release();
        if (!released.ok && selectionVersion.current === version) {
          setError(
            describePendingFailure(
              released,
              "대기 메시지를 보내지 못했어요. 「보내기」 를 다시 눌러 주세요.",
            ),
          );
        }
      }
      return true;
    } finally {
      enqueueing.current = false;
    }
  }

  /**
   * 입력창과 질문 카드의 보내기가 지나는 자리다. 보통 보내기와 대기 경로 중 하나를 고른다.
   *
   * <p>답이 오는 중이거나 대기 줄이 멈춰 있으면 대기 경로다. 대기 메시지는 글만 받으므로 사진을 실었으면 보내지 않는다.
   */
  async function submit(
    attachmentIds: number[],
    replacementText?: string,
    attachments?: MessageAttachment[],
  ): Promise<boolean> {
    if (!sending && !pending.queue.held)
      return send(attachmentIds, replacementText, attachments);
    if (attachmentIds.length > 0) {
      // 답이 오는 동안은 사진 첨부가 잠겨 있다. 여기 오는 것은 멈춰 둔 대기 줄이 있을 때 사진을 붙인 경우다.
      if (!sending)
        setError(
          "사진은 대기 중인 메시지를 보내거나 취소한 뒤에 보낼 수 있어요.",
        );
      return false;
    }
    return queueMessage(
      (replacementText ?? draft).trim(),
      replacementText === undefined,
    );
  }

  /** 대기 메시지를 취소하고 그 글을 입력창에 되돌린다. 쓰던 글이 있으면 그 뒤에 줄을 바꿔 붙인다. */
  async function cancelPendingMessage(pendingId: number) {
    const version = selectionVersion.current;
    setPendingBusy(true);
    try {
      const result = await pending.cancel(pendingId);
      if (selectionVersion.current !== version) return;
      if (result.ok) {
        setError(null);
        // 화면이 그 줄을 모르면 되돌릴 글이 없다. 입력창에 빈 줄만 붙지 않게 그대로 둔다.
        if (result.data.length === 0) return;
        setDraft((current) =>
          current.trim().length === 0
            ? result.data
            : `${current}\n${result.data}`,
        );
        return;
      }
      setError(
        describePendingFailure(
          result,
          "대기 메시지를 취소하지 못했어요. 다시 시도해 주세요.",
        ),
      );
      // 취소하는 사이에 이미 보내졌다. 그 글은 사용자 메시지로 저장됐으므로 되돌리지 않고 대기 줄만 맞춘다.
      if (result.code === "PENDING_MESSAGE_NOT_FOUND") await pending.reload();
    } finally {
      setPendingBusy(false);
    }
  }

  /** 멈춰 둔 대기 줄을 푼다. turn 이 돌고 있으면 그 turn 이 끝난 뒤 간다. */
  async function releasePendingMessages() {
    const version = selectionVersion.current;
    setPendingBusy(true);
    try {
      const result = await pending.release();
      if (selectionVersion.current !== version) return;
      if (result.ok) setError(null);
      else
        setError(
          describePendingFailure(
            result,
            "대기 메시지를 보내지 못했어요. 「보내기」 를 다시 눌러 주세요.",
          ),
        );
    } finally {
      setPendingBusy(false);
    }
  }
  return { queueMessage, submit, cancelPendingMessage, releasePendingMessages };
}
export type ConversationQueue = ReturnType<typeof useConversationQueue>;
