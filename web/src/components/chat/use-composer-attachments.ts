"use client";

import { useEffect, useLayoutEffect, type ChangeEvent } from "react";
import { describeError } from "@/components/error-message";
import {
  deleteConversationAttachment,
  uploadConversationAttachment,
} from "@/lib/chat-api";
import { Props } from "./composer-types";
import {
  AttachmentItem,
  buildThumbnail,
  ACCEPTED_TYPES,
  MAX_ATTACHMENTS,
  MAX_ATTACHMENT_BYTES,
} from "./composer-attachment-utils";
import type { ComposerState } from "./use-composer-state";
import type { ComposerModel } from "./use-composer-model";

type Context = Pick<
  ComposerState,
  | "itemsRef"
  | "items"
  | "outgoingRef"
  | "setOutgoing"
  | "mountedRef"
  | "pendingRemovalRef"
  | "sendingItemsRef"
  | "setItems"
  | "setPickNotice"
> &
  Pick<Props, "running"> &
  Pick<ComposerModel, "ensureConversationId">;

export function useComposerAttachments({
  itemsRef,
  items,
  outgoingRef,
  setOutgoing,
  mountedRef,
  pendingRemovalRef,
  sendingItemsRef,
  setItems,
  setPickNotice,
  running,
  ensureConversationId,
}: Context) {
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
      for (const item of [
        ...itemsRef.current,
        ...(outgoingRef.current?.items ?? []),
      ]) {
        if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
        if (
          item.status === "done" &&
          item.attachmentId !== null &&
          item.conversationId !== null &&
          !sending.has(item.key)
        ) {
          void deleteAttachment(item.conversationId, item.attachmentId);
        }
      }
      itemsRef.current = [];
      outgoingRef.current = null;
      pendingRemoval.clear();
    };
  }, []);

  function updateItem(key: string, patch: Partial<AttachmentItem>) {
    const updated = itemsRef.current.map((item) =>
      item.key === key ? { ...item, ...patch } : item,
    );
    itemsRef.current = updated;
    setItems(updated);
    if (outgoingRef.current) {
      const outgoing = {
        ...outgoingRef.current,
        items: outgoingRef.current.items.map((item) =>
          item.key === key ? { ...item, ...patch } : item,
        ),
      };
      outgoingRef.current = outgoing;
      setOutgoing(outgoing);
    }
  }

  /** 지우는 단추가 눌린 첨부다. 업로드 응답이 오면 DELETE 로 마무리한다 */
  function finalizeRemovalIfRequested(
    key: string,
    targetConversationId: string,
    attachmentId: number | null,
  ) {
    if (!pendingRemovalRef.current.has(key)) return false;
    pendingRemovalRef.current.delete(key);
    if (attachmentId !== null)
      void deleteAttachment(targetConversationId, attachmentId);
    return true;
  }

  async function deleteAttachment(
    targetConversationId: string,
    attachmentId: number,
  ) {
    try {
      await deleteConversationAttachment(targetConversationId, attachmentId);
    } catch {
      // 지우기 요청이 실패해도 화면은 이미 그 미리보기를 치웠다. 사용자가 다시 시도할 자리가 없어 조용히 넘어간다.
    }
  }

  async function uploadOne(
    file: File,
    targetConversationId: string,
    key: string,
  ) {
    const current = [
      ...itemsRef.current,
      ...(outgoingRef.current?.items ?? []),
    ].find((item) => item.key === key);
    const thumbnail = current?.previewUrl
      ? Promise.resolve("")
      : buildThumbnail(file).catch(() => "");
    void thumbnail.then((previewUrl) => {
      if (!previewUrl) return;
      const item = [
        ...itemsRef.current,
        ...(outgoingRef.current?.items ?? []),
      ].find((candidate) => candidate.key === key);
      if (!mountedRef.current || !item || pendingRemovalRef.current.has(key)) {
        URL.revokeObjectURL(previewUrl);
        return;
      }
      updateItem(key, { previewUrl });
    });

    try {
      const form = new FormData();
      form.append("file", file, file.name);
      const response = await uploadConversationAttachment(
        targetConversationId,
        form,
      );
      const payload = (await response.json()) as {
        id?: number;
        code?: string;
        message?: string;
      };
      if (!mountedRef.current) {
        // 올리는 동안 대화를 바꿨다. 이 첨부를 보낼 자리가 사라졌으므로 서버에서도 지운다.
        if (response.ok && payload.id)
          void deleteAttachment(targetConversationId, payload.id);
        return;
      }
      if (!response.ok || !payload.id) {
        finalizeRemovalIfRequested(key, targetConversationId, null);
        updateItem(key, {
          status: "error",
          errorMessage: describeError(
            payload.code ?? "INTERNAL_ERROR",
            payload.message ?? "사진을 올리지 못했어요.",
          ),
        });
        return;
      }
      if (finalizeRemovalIfRequested(key, targetConversationId, payload.id))
        return;
      updateItem(key, { status: "done", attachmentId: payload.id });
    } catch {
      finalizeRemovalIfRequested(key, targetConversationId, null);
      updateItem(key, {
        status: "error",
        errorMessage: "사진을 올리지 못했어요. 다시 시도해 주세요.",
      });
    }
  }

  async function handleFiles(event: ChangeEvent<HTMLInputElement>) {
    if (running || outgoingRef.current) return;
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length === 0) return;

    const rejectedFormatCount = files.filter(
      (file) => !ACCEPTED_TYPES.includes(file.type),
    ).length;
    const accepted = files.filter((file) => ACCEPTED_TYPES.includes(file.type));
    // 상한은 한 번에 고를 때만 센다. 이미 붙은 첨부를 빼고 남은 자리만큼만 올린다.
    const remainingSlots = Math.max(
      0,
      MAX_ATTACHMENTS - itemsRef.current.length,
    );
    const overflowCount = Math.max(0, accepted.length - remainingSlots);
    const capped = accepted.slice(0, remainingSlots);
    const oversize = capped.filter((file) => file.size > MAX_ATTACHMENT_BYTES);
    const toUpload = capped.filter((file) => file.size <= MAX_ATTACHMENT_BYTES);

    const notices: string[] = [];
    if (rejectedFormatCount > 0) {
      notices.push(
        `이미지 파일만 올릴 수 있어요. ${rejectedFormatCount}장은 올리지 못했어요.`,
      );
    }
    if (overflowCount > 0) {
      notices.push(
        `한 번에 ${MAX_ATTACHMENTS}장까지 올릴 수 있어요. ${overflowCount}장은 올리지 못했어요.`,
      );
    }
    if (oversize.length > 0) {
      notices.push(
        `사진 한 장은 10MB까지 올릴 수 있어요. ${oversize.length}장은 올리지 못했어요.`,
      );
    }
    setPickNotice(notices.length > 0 ? notices.join(" ") : null);

    if (toUpload.length === 0) return;

    const newItems = toUpload.map((file) => ({
      key: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
      file,
      previewUrl: "",
      status: "uploading" as const,
      attachmentId: null,
      errorMessage: null,
      conversationId: null,
    }));
    const nextItems = [...itemsRef.current, ...newItems];
    itemsRef.current = nextItems;
    setItems(nextItems);

    const targetConversationId = await ensureConversationId();
    if (targetConversationId === null || !mountedRef.current) {
      for (const item of newItems) pendingRemovalRef.current.delete(item.key);
      const retained = itemsRef.current.filter(
        (item) => !newItems.some((created) => created.key === item.key),
      );
      itemsRef.current = retained;
      if (mountedRef.current) setItems(retained);
      return;
    }

    for (let index = 0; index < toUpload.length; index++) {
      const item = newItems[index]!;
      if (!itemsRef.current.some((current) => current.key === item.key))
        continue;
      updateItem(item.key, { conversationId: targetConversationId });
      void uploadOne(toUpload[index]!, targetConversationId, item.key);
    }
  }

  function removeItem(key: string) {
    if (running) return;
    // 부수 효과는 updater 밖에서 한 번만 부른다. 개발 모드의 StrictMode 는 updater 를 두 번 돌린다.
    const target = itemsRef.current.find((item) => item.key === key);
    if (!target) return;
    if (target.previewUrl) URL.revokeObjectURL(target.previewUrl);
    if (target.status === "uploading" && target.conversationId !== null) {
      pendingRemovalRef.current.add(key);
    } else if (target.status === "uploading") {
      pendingRemovalRef.current.delete(key);
    } else if (target.status === "done" && target.attachmentId !== null) {
      if (target.conversationId !== null) {
        void deleteAttachment(target.conversationId, target.attachmentId);
      }
    }
    itemsRef.current = itemsRef.current.filter((item) => item.key !== key);
    setItems((previous) => previous.filter((item) => item.key !== key));
  }

  return {
    updateItem,
    finalizeRemovalIfRequested,
    deleteAttachment,
    uploadOne,
    handleFiles,
    removeItem,
  };
}
export type ComposerAttachments = ReturnType<typeof useComposerAttachments>;
