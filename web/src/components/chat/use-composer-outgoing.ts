"use client";

import { useCallback, useEffect, useRef } from "react";
import type { Props } from "./composer-types";
import type { ComposerState } from "./use-composer-state";
import type { ComposerAttachments } from "./use-composer-attachments";
import type { OutgoingMessage } from "./composer-attachment-utils";

type Context = ComposerState &
  Pick<
    Props,
    | "value"
    | "onChange"
    | "onSend"
    | "onOutgoingChange"
    | "running"
    | "canQueue"
  > &
  ComposerAttachments;

export function useComposerOutgoing({
  itemsRef,
  setItems,
  outgoing,
  outgoingRef,
  setOutgoing,
  setPickNotice,
  mountedRef,
  sendingItemsRef,
  sendDisabled,
  value,
  onChange,
  onSend,
  onOutgoingChange,
  running,
  savingModel,
  canQueue,
  updateItem,
  uploadOne,
  deleteAttachment,
}: Context) {
  const submitting = useRef(false);

  const publish = useCallback(
    (message: OutgoingMessage | null) => {
      outgoingRef.current = message;
      setOutgoing(message);
    },
    [outgoingRef, setOutgoing],
  );

  function retry(key: string) {
    const item = outgoingRef.current?.items.find(
      (candidate) => candidate.key === key,
    );
    if (!item || item.status !== "error" || item.conversationId === null)
      return;
    updateItem(key, { status: "uploading", errorMessage: null });
    void uploadOne(item.file, item.conversationId, key);
  }

  function omit(key: string) {
    const message = outgoingRef.current;
    const item = message?.items.find((candidate) => candidate.key === key);
    if (!message || !item || item.status !== "error") return;
    if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
    if (item.attachmentId !== null && item.conversationId !== null)
      void deleteAttachment(item.conversationId, item.attachmentId);
    publish({
      ...message,
      items: message.items.filter((candidate) => candidate.key !== key),
    });
  }

  const sendOutgoing = useCallback(async () => {
    const message = outgoingRef.current;
    if (
      !message ||
      submitting.current ||
      running ||
      savingModel ||
      message.items.some((item) => item.status !== "done")
    )
      return;
    submitting.current = true;
    sendingItemsRef.current = message.items;
    // 같은 렌더에서 임시 업로드 줄을 보내기 훅의 내 메시지로 바꾼다. 답이 끝날 때까지 입력창에 남기지 않는다.
    publish(null);
    let succeeded = false;
    try {
      const attachments = message.items.map((item) => ({
        id: item.attachmentId!,
        originalName: item.file.name,
        byteSize: item.file.size,
        visible: true,
        expiresAt: "",
      }));
      succeeded = await onSend(
        attachments.map((item) => item.id),
        message.text,
        attachments,
      );
    } finally {
      submitting.current = false;
      sendingItemsRef.current = [];
      if (succeeded || !mountedRef.current) {
        for (const item of message.items)
          if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
      } else {
        publish({
          ...message,
          errorMessage: "메시지를 보내지 못했어요. 다시 시도해 주세요.",
        });
      }
    }
  }, [
    outgoingRef,
    running,
    savingModel,
    sendingItemsRef,
    onSend,
    mountedRef,
    publish,
  ]);

  function trySend() {
    if (sendDisabled || outgoingRef.current || (running && !canQueue)) return;
    if (itemsRef.current.length === 0) {
      void onSend([]);
      return;
    }
    publish({
      text: value.trim(),
      items: itemsRef.current,
      errorMessage: null,
      retry,
      omit,
      retrySend: () => {
        if (outgoingRef.current)
          publish({ ...outgoingRef.current, errorMessage: null });
      },
    });
    itemsRef.current = [];
    setItems([]);
    setPickNotice(null);
    onChange("");
  }

  useEffect(() => {
    onOutgoingChange(outgoing);
    if (outgoing && !outgoing.errorMessage) void sendOutgoing();
  }, [outgoing, onOutgoingChange, sendOutgoing]);

  return { trySend };
}
