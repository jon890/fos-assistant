"use client";

import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
} from "react";
import { ArrowUp, ImagePlus, LoaderCircle, Square, X } from "lucide-react";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { cn } from "cn";
import { attachmentPlaceholder } from "./variants";
import type { AgentView } from "@/lib/agent";
import { describeError } from "../error-message";
import {
  AgentMention,
  filterAgents,
  findMention,
  mentionOptionId,
} from "./agent-mention";
import { SkillCommandMenu } from "./skill-command-menu";
import {
  filterSkillNames,
  findSkillQuery,
  skillOptionId,
  withSkillCommand,
} from "./skill-command";
import {
  ModelPicker,
  ModelTierPicker,
  type ModelChoice,
  type ModelChoiceSaveResult,
  type ModelTierSaveResult,
} from "./model-picker";
import type { Conversation } from "../shell/conversations-provider";
import { saveConversationTier, type ModelTierCode } from "@/lib/model-tiers";

type Props = {
  value: string;
  disabled: boolean;
  onChange(value: string): void;
  /** 전송이 실제로 끝났는지를 돌려준다. 실패하면 미리보기를 지우지 않는다 */
  onSend(attachmentIds: number[]): Promise<boolean>;
  /** 대화가 아직 없으면 null. 사진을 고르면 이 값이 없는 채로 첫 사진을 올릴 수 없다 */
  conversationId: string | null;
  agentCode: string;
  /** 이 에이전트의 대화에 사진을 붙일 수 있다. 거짓이면 사진 단추를 그리지 않는다 */
  acceptsAttachments: boolean;
  onConversationCreated(id: string): void;
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
  /**
   * 입력칸 맨 앞에 `/` 를 치면 이 스킬 이름 목록을 띄운다. 비었으면 스킬이 없다고 알린다.
   * 흐름이 붙은 에이전트이거나 목록을 아직 읽지 못했으면 주지 않고, 그때는 목록을 띄우지 않는다.
   */
  skillNames?: string[];
  /**
   * 보내기를 막는 일이 도는지 알린다. 빈 대화를 만드는 요청이나 끝나지 않은 첨부가 그렇다.
   * 입력창을 거치지 않고 보내는 추천 질문도 이 동안은 막아야 대화가 둘 생기지 않는다.
   */
  onBlockingChange?(blocking: boolean): void;
  /** 이 대화에 적힌 모델 선택이다. 대화가 아직 없으면 null */
  modelChoice: ModelChoice | null;
  /**
   * 대화는 있는데 그 대화에 적힌 모델 선택을 아직 모른다. 대화 목록이 오기 전이 그렇다.
   * 이때 고르게 하면 모르는 값을 「기본」 으로 보고 저장해 적힌 모델을 지운다. 그래서 단추를 막는다.
   */
  modelChoiceUnknown: boolean;
  modelSelectionMode: "DEFAULT" | "TIER" | "CUSTOM" | null;
  modelTier: ModelTierCode | null;
  /** 모델 선택을 저장한 뒤 서버가 돌려준 대화 한 줄을 알린다 */
  onModelChoiceSaved(conversation: Conversation): void;
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
  /** 이 첨부가 올라간 대화의 공개 식별자다. 지울 때 이 식별자로 서버 DELETE 를 부른다 */
  conversationId: string;
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
    throw new Error("캔버스를 만들지 못했어요.");
  }
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();

  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (result) =>
        result
          ? resolve(result)
          : reject(new Error("미리보기를 만들지 못했어요.")),
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
  skillNames,
  onBlockingChange,
  modelChoice,
  modelChoiceUnknown,
  modelSelectionMode,
  modelTier,
  onModelChoiceSaved,
}: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const [items, setItems] = useState<AttachmentItem[]>([]);
  const [pickNotice, setPickNotice] = useState<string | null>(null);
  /** 빈 대화를 만드는 요청이 도는 중이다. 아직 첨부 목록에 아무것도 없어 `items` 로는 알 수 없다 */
  const [creatingConversation, setCreatingConversation] = useState(false);
  /** 빈 대화를 만드는 요청이 진행 중이면 그 Promise 를 담아 다시 쓴다. 연달아 고르면 두 번 도는 것을 막는다 */
  const creatingConversationRef = useRef<Promise<string | null> | null>(null);
  /** 모델 선택을 저장하는 중이다. 그동안 보내면 바꾸기 전의 모델로 돌 수 있어 보내기를 막는다 */
  const [savingModel, setSavingModel] = useState(false);
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
  const [dismissedMentionStart, setDismissedMentionStart] = useState<
    number | null
  >(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const skillListId = useId();
  /** `Esc` 로 `/` 목록을 닫았다. 첫 낱말을 다 치거나 `/` 를 지울 때까지 다시 띄우지 않는다 */
  const [skillDismissed, setSkillDismissed] = useState(false);
  const [skillIndex, setSkillIndex] = useState(0);
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
        if (
          item.status === "done" &&
          item.attachmentId !== null &&
          !sending.has(item.key)
        ) {
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
  const openMention =
    found !== null && found.start !== dismissedMentionStart ? found : null;
  const mentionMatches =
    mention && openMention
      ? filterAgents(mention.agents, openMention.query)
      : [];
  const activeMentionIndex = Math.min(
    mentionIndex,
    Math.max(0, mentionMatches.length - 1),
  );
  const skillQuery =
    skillNames && !skillDismissed ? findSkillQuery(value, caret) : null;
  const skillMatches =
    skillNames && skillQuery !== null
      ? filterSkillNames(skillNames, skillQuery)
      : [];
  // 맞는 이름이 없으면 띄우지 않는다. `/usr/bin` 처럼 커맨드가 아닌 글을 칠 때 목록이 가리지 않게 한다.
  // 스킬이 없는 에이전트는 `/` 만 친 동안에만 스킬이 없다고 알린다.
  const skillMenuOpen =
    skillNames !== undefined &&
    skillQuery !== null &&
    (skillNames.length === 0 ? skillQuery === "" : skillMatches.length > 0);
  const activeSkillIndex = Math.min(
    skillIndex,
    Math.max(0, skillMatches.length - 1),
  );

  function changeValue(nextValue: string, nextCaret: number) {
    const next = mention ? findMention(nextValue, nextCaret) : null;
    if (next === null || next.start !== dismissedMentionStart)
      setDismissedMentionStart(null);
    if (findSkillQuery(nextValue, nextCaret) === null) setSkillDismissed(false);
    setCaret(nextCaret);
    setMentionIndex(0);
    setSkillIndex(0);
    onChange(nextValue);
  }

  function pickSkill(name: string) {
    const next = withSkillCommand(value, name);
    pendingCaretRef.current = next.caret;
    changeValue(next.value, next.caret);
  }

  function pickMention(code: string) {
    if (!mention || !openMention) return;
    const end = textareaRef.current?.selectionStart ?? caret;
    pendingCaretRef.current = openMention.start;
    mention.onPick(code);
    changeValue(
      value.slice(0, openMention.start) + value.slice(end),
      openMention.start,
    );
  }

  const uploading = items.some((item) => item.status === "uploading");
  const hasBlockingAttachment = items.some((item) => item.status !== "done");
  const blocking = creatingConversation || hasBlockingAttachment || savingModel;
  const sendDisabled = disabled || value.trim().length === 0 || blocking;

  useEffect(() => {
    onBlockingChange?.(blocking);
  }, [blocking, onBlockingChange]);

  function updateItem(key: string, patch: Partial<AttachmentItem>) {
    setItems((previous) =>
      previous.map((item) => (item.key === key ? { ...item, ...patch } : item)),
    );
  }

  async function ensureConversationId(): Promise<string | null> {
    if (conversationId !== null) return conversationId;
    if (creatingConversationRef.current) return creatingConversationRef.current;

    const promise = (async () => {
      setCreatingConversation(true);
      try {
        const response = await fetch("/api/chat/conversations", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ agentCode }),
        });
        const payload = (await response.json()) as {
          conversationId?: string;
          code?: string;
          message?: string;
        };
        if (!response.ok || !payload.conversationId) {
          setPickNotice(
            "대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
          );
          return null;
        }
        // 요청 도중 대화를 바꿨으면 부모는 이미 다른 대화를 보고 있다. 그 선택을 덮지 않는다.
        if (!mountedRef.current) return null;
        onConversationCreated(payload.conversationId);
        return payload.conversationId;
      } catch {
        setPickNotice("대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        return null;
      } finally {
        creatingConversationRef.current = null;
        if (mountedRef.current) setCreatingConversation(false);
      }
    })();
    creatingConversationRef.current = promise;
    return promise;
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
      await fetch(
        `/api/chat/conversations/${targetConversationId}/attachments/${attachmentId}`,
        {
          method: "DELETE",
        },
      );
    } catch {
      // 지우기 요청이 실패해도 화면은 이미 그 미리보기를 치웠다. 사용자가 다시 시도할 자리가 없어 조용히 넘어간다.
    }
  }

  async function uploadOne(file: File, targetConversationId: string) {
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
      {
        key,
        previewUrl,
        status: "uploading",
        attachmentId: null,
        errorMessage: null,
        conversationId: targetConversationId,
      },
    ]);

    try {
      const form = new FormData();
      form.append("file", file, file.name);
      const response = await fetch(
        `/api/chat/conversations/${targetConversationId}/attachments`,
        {
          method: "POST",
          body: form,
        },
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
    if (running) return;
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length === 0) return;

    const rejectedFormatCount = files.filter(
      (file) => !ACCEPTED_TYPES.includes(file.type),
    ).length;
    const accepted = files.filter((file) => ACCEPTED_TYPES.includes(file.type));
    // 상한은 한 번에 고를 때만 센다. 이미 붙은 첨부를 빼고 남은 자리만큼만 올린다.
    const remainingSlots = Math.max(0, MAX_ATTACHMENTS - items.length);
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

    const targetConversationId = await ensureConversationId();
    if (targetConversationId === null || !mountedRef.current) return;

    for (const file of toUpload) {
      void uploadOne(file, targetConversationId);
    }
  }

  /**
   * 고른 모델을 대화에 저장한다. 대화가 아직 없으면 사진을 먼저 올릴 때처럼 빈 대화를 만든다.
   *
   * <p>대화를 만든 것은 `ensureConversationId` 가 이미 알렸으므로 여기서 다시 알리지 않는다.
   */
  async function saveModelChoice(
    choice: ModelChoice,
  ): Promise<ModelChoiceSaveResult> {
    setSavingModel(true);
    try {
      const targetConversationId = await ensureConversationId();
      // 빈 대화를 만들지 못했으면 `ensureConversationId` 가 이미 알렸다.
      if (targetConversationId === null || !mountedRef.current)
        return "reported";
      const response = await fetch(
        `/api/chat/conversations/${targetConversationId}/model`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(choice),
        },
      );
      if (!response.ok) return "failed";
      // 기다리는 동안 대화를 바꿨어도 알린다. 받은 줄은 그 대화의 것이라 목록의 그 줄만 바뀐다.
      onModelChoiceSaved((await response.json()) as Conversation);
      return "saved";
    } catch {
      return "failed";
    } finally {
      if (mountedRef.current) setSavingModel(false);
    }
  }

  /** 고른 단계를 대화에 저장한다. 기본값으로 돌아가기도 같은 경로에서 명시적으로 적는다. */
  async function saveModelTier(
    mode: "DEFAULT" | "TIER",
    tier: ModelTierCode | null,
  ): Promise<ModelTierSaveResult> {
    setSavingModel(true);
    try {
      const targetConversationId = await ensureConversationId();
      if (targetConversationId === null || !mountedRef.current)
        return "reported";
      const result = await saveConversationTier<Conversation>(
        targetConversationId,
        mode,
        tier,
      );
      if (!result.ok) return "failed";
      onModelChoiceSaved(result.data);
      return "saved";
    } catch {
      return "failed";
    } finally {
      if (mountedRef.current) setSavingModel(false);
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
      (item): item is AttachmentItem & { attachmentId: number } =>
        item.status === "done" && item.attachmentId !== null,
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
        <div
          data-testid="attachment-previews"
          className="mb-2 flex items-center gap-2 overflow-x-auto pb-1"
        >
          {items.map((item) => (
            <div key={item.key} className="relative shrink-0">
              {item.status === "error" ? (
                <div className={attachmentPlaceholder({ size: "preview" })}>
                  <span>{item.errorMessage}</span>
                </div>
              ) : (
                <div className="relative h-16 w-16 overflow-hidden rounded-md border border-border bg-muted">
                  {item.previewUrl ? (
                    // eslint 설정이 없는 저장소라 next/image 대신 object URL 을 바로 그린다.
                    <img
                      src={item.previewUrl}
                      alt=""
                      className="h-full w-full object-cover"
                    />
                  ) : null}
                  {item.status === "uploading" ? (
                    <span
                      data-testid="attachment-uploading"
                      aria-hidden="true"
                      className="absolute inset-0 flex items-center justify-center bg-background/60"
                    >
                      <LoaderCircle className="size-4 animate-spin text-primary motion-reduce:animate-none" />
                    </span>
                  ) : null}
                </div>
              )}
              <TooltipButton
                label="사진 지우기"
                variant="outline"
                size="icon-xs"
                onClick={() => removeItem(item.key)}
                // 보내는 동안 지우면 막 메시지에 묶인 첨부에 DELETE 가 간다.
                disabled={disabled || running}
                className="absolute -right-1.5 -top-1.5 size-5 rounded-full bg-background"
              >
                <X aria-hidden="true" />
              </TooltipButton>
            </div>
          ))}
        </div>
      ) : null}

      {pickNotice ? (
        <p
          data-testid="attachment-notice"
          className="mb-2 text-xs text-muted-foreground"
        >
          {pickNotice}
        </p>
      ) : null}

      <div
        data-testid="composer-shell"
        className={cn(
          "relative flex items-end gap-2 p-1.5 pl-4",
          "rounded-3xl border border-border bg-background focus-within:border-primary",
        )}
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
        {skillNames && skillMenuOpen && skillQuery !== null ? (
          <SkillCommandMenu
            id={skillListId}
            names={skillNames}
            query={skillQuery}
            activeIndex={activeSkillIndex}
            onPick={pickSkill}
          />
        ) : null}
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          aria-label="메시지"
          aria-controls={
            openMention
              ? mentionListId
              : skillMenuOpen
                ? skillListId
                : undefined
          }
          aria-activedescendant={
            openMention && mentionMatches.length > 0
              ? mentionOptionId(mentionListId, activeMentionIndex)
              : skillMenuOpen && skillMatches.length > 0
                ? skillOptionId(skillListId, activeSkillIndex)
                : undefined
          }
          onChange={(event) =>
            changeValue(event.target.value, event.target.selectionStart)
          }
          onSelect={(event) => setCaret(event.currentTarget.selectionStart)}
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={() => {
            composing.current = false;
          }}
          onKeyDown={(event) => {
            const imeComposing =
              composing.current || event.nativeEvent.isComposing;
            if (skillMenuOpen && event.key === "Escape") {
              // `@` 목록과 같다. 목록만 닫고 대화 화면의 중지나 패널 닫기로 넘기지 않는다.
              event.preventDefault();
              event.stopPropagation();
              setSkillDismissed(true);
              return;
            }
            if (skillMenuOpen && !imeComposing && skillMatches.length > 0) {
              if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault();
                const step = event.key === "ArrowDown" ? 1 : -1;
                setSkillIndex(
                  (activeSkillIndex + step + skillMatches.length) %
                    skillMatches.length,
                );
                return;
              }
              if (
                (event.key === "Enter" && !event.shiftKey) ||
                event.key === "Tab"
              ) {
                // 고를 이름이 있을 때만 Enter 가 고르기다. 스킬이 없다는 문구만 떠 있으면 평소처럼 보낸다.
                event.preventDefault();
                pickSkill(skillMatches[activeSkillIndex]!);
                return;
              }
            }
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
                  setMentionIndex(
                    (activeMentionIndex + step + mentionMatches.length) %
                      mentionMatches.length,
                  );
                }
                return;
              }
              if (
                (event.key === "Enter" && !event.shiftKey) ||
                (event.key === "Tab" && mentionMatches.length > 0)
              ) {
                // 목록이 떠 있는 동안의 Enter 는 고르기다. 맞는 것이 없어도 보내지 않는다.
                // Shift+Enter 는 고르지 않고 평소처럼 줄을 바꾼다. 줄이 바뀌면 `@` 뒤에 공백이 생겨 목록이 닫힌다.
                event.preventDefault();
                const picked = mentionMatches[activeMentionIndex];
                if (picked) pickMention(picked.code);
                return;
              }
            }
            if (
              event.key !== "Enter" ||
              event.shiftKey ||
              composing.current ||
              event.nativeEvent.isComposing ||
              running
            ) {
              return;
            }
            event.preventDefault();
            void trySend();
          }}
          disabled={disabled}
          placeholder={
            mention ? "@로 에이전트를 불러요" : "무엇을 도와드릴까요?"
          }
          className={cn(
            "max-h-[7.5rem] min-h-10 flex-1 resize-none overflow-y-auto py-2",
            "bg-transparent text-base leading-6 outline-none disabled:opacity-50",
          )}
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
            <TooltipButton
              label="사진 첨부"
              variant="outline"
              size="icon"
              onClick={() => fileInputRef.current?.click()}
              disabled={disabled || running}
              className="size-10 shrink-0 rounded-full"
            >
              <ImagePlus aria-hidden="true" className="size-5" />
            </TooltipButton>
          </>
        ) : null}
        {running ? (
          <TooltipButton
            label="중지"
            passEscape
            variant="default"
            disabled={!canStop}
            onClick={onStop}
            size="icon"
            className="size-10 shrink-0 rounded-full"
          >
            <Square aria-hidden="true" className="size-4 fill-current" />
          </TooltipButton>
        ) : (
          <TooltipButton
            label="보내기"
            variant="default"
            type="submit"
            disabled={sendDisabled}
            size="icon"
            className="size-10 shrink-0 rounded-full"
          >
            {uploading ? (
              <LoaderCircle
                aria-hidden="true"
                className="size-4 animate-spin motion-reduce:animate-none"
              />
            ) : (
              <ArrowUp aria-hidden="true" className="size-5" />
            )}
          </TooltipButton>
        )}
      </div>
      {/* 알약 안에 두면 좁은 폭에서 입력칸이 줄어든다. 그래서 알약 아래 줄에 둔다. */}
      <div className="mt-1 flex min-w-0 px-2">
        <div className="flex min-w-0 flex-col gap-1">
          <ModelTierPicker
            agentCode={agentCode}
            mode={modelSelectionMode}
            tier={modelTier}
            onChange={saveModelTier}
            disabled={disabled || agentCode.length === 0 || modelChoiceUnknown}
            advancedPicker={
              <ModelPicker
                agentCode={agentCode}
                choice={modelChoice}
                onChange={saveModelChoice}
                disabled={
                  disabled || agentCode.length === 0 || modelChoiceUnknown
                }
                inSettings
              />
            }
          />
        </div>
      </div>
    </form>
  );
}
