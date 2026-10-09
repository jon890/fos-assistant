"use client";

import { useId, useRef, useState } from "react";
import { Props } from "./composer-types";
import {
  AttachmentItem,
  type OutgoingMessage,
} from "./composer-attachment-utils";
export function useComposerState({ disabled, value }: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<AttachmentItem[]>([]);
  const [outgoing, setOutgoing] = useState<OutgoingMessage | null>(null);
  const outgoingRef = useRef<OutgoingMessage | null>(null);
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

  const uploading = items.some((item) => item.status === "uploading");
  const hasBlockingAttachment = items.some((item) => item.status !== "done");
  const blocking = creatingConversation || hasBlockingAttachment || savingModel;
  const sendDisabled =
    disabled ||
    value.trim().length === 0 ||
    creatingConversation ||
    savingModel ||
    outgoing !== null;
  return {
    textareaRef,
    fileInputRef,
    items,
    setItems,
    outgoing,
    setOutgoing,
    outgoingRef,
    pickNotice,
    setPickNotice,
    creatingConversation,
    setCreatingConversation,
    creatingConversationRef,
    savingModel,
    setSavingModel,
    pendingRemovalRef,
    mountedRef,
    itemsRef,
    sendingItemsRef,
    mentionListId,
    caret,
    setCaret,
    dismissedMentionStart,
    setDismissedMentionStart,
    mentionIndex,
    setMentionIndex,
    skillListId,
    skillDismissed,
    setSkillDismissed,
    skillIndex,
    setSkillIndex,
    pendingCaretRef,
    uploading,
    hasBlockingAttachment,
    blocking,
    sendDisabled,
  };
}
export type ComposerState = ReturnType<typeof useComposerState>;
