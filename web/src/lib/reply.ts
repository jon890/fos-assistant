/** 답장 상자의 작은 그림 하나다. `ChatDtos.ReplyImageView` 를 그대로 받는다 */
export type ReplyImage = {
  kind: "GENERATED" | "ATTACHMENT";
  path: string | null;
  attachmentId: number | null;
  deleted: boolean;
};

/** 보낸 답장 위의 상자다. `ChatDtos.ReplyView` 를 그대로 받는다(스펙 chat-reference REQ6) */
export type ReplyView = {
  messageId: number;
  role: "USER" | "ASSISTANT" | null;
  excerpt: string;
  available: boolean;
  images: ReplyImage[];
};

/** 입력창 위에 놓인 집은 것이다. 보내면 `reply` 요청이 되고 보낸 줄의 상자가 된다(스펙 chat-reference REQ1, REQ4) */
export type ReplyTarget = ReplyView & {
  attachmentId: number | null;
  imagePath: string | null;
};

type PickableMessage = {
  id: number | string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  attachments?: { id: number; visible: boolean }[];
  artifacts?: { path: string; deleted: boolean; kind?: string }[];
  steer?: unknown;
};

/** 상자 줄마다 상한이다. 서버의 `ReplyViews.LINE_LIMIT` 와 같다 */
const LINE_LIMIT = 200;

/** 빈 줄이 아닌 앞 두 줄이다. 서버의 `ReplyViews.excerpt` 와 같게 자른다 */
export function excerptOf(text: string): string {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .slice(0, 2)
    .map((line) =>
      line.length > LINE_LIMIT ? `${line.slice(0, LINE_LIMIT)}…` : line,
    )
    .join("\n");
}

/**
 * 메시지에서 집은 것을 만든다. `pick` 이 없으면 메시지 전체(그 안의 사진과 그림 포함)다. 저장되지 않은 줄, 알림 줄, steer 말풍선은
 * 집지 않는다(스펙 DECISION14).
 */
export function replyTargetOf(
  message: PickableMessage,
  pick?: { attachmentId?: number; imagePath?: string },
): ReplyTarget | null {
  if (
    typeof message.id !== "number" ||
    message.role === "SYSTEM" ||
    (message.steer !== undefined && message.steer !== null)
  )
    return null;
  // 그림 한 장을 집었으면 사진은 담지 않는다.
  const photos: ReplyImage[] = (
    pick?.imagePath === undefined ? (message.attachments ?? []) : []
  )
    .filter(
      (it) => pick?.attachmentId === undefined || it.id === pick.attachmentId,
    )
    .map((it) => ({
      kind: "ATTACHMENT",
      path: null,
      attachmentId: it.id,
      deleted: !it.visible,
    }));
  const images: ReplyImage[] = (message.artifacts ?? [])
    .filter((it) => it.kind === "IMAGE" && pick?.attachmentId === undefined)
    .filter((it) => pick?.imagePath === undefined || it.path === pick.imagePath)
    .map((it) => ({
      kind: "GENERATED",
      path: it.path,
      attachmentId: null,
      deleted: it.deleted,
    }));
  return {
    messageId: message.id,
    role: message.role,
    excerpt: excerptOf(message.content),
    available: true,
    images: [...photos, ...images],
    attachmentId: pick?.attachmentId ?? null,
    imagePath: pick?.imagePath ?? null,
  };
}

/** 보내기 요청의 `reply` 칸이다 */
export function replyRequestOf(target: ReplyTarget) {
  return {
    messageId: target.messageId,
    attachmentId: target.attachmentId,
    imagePath: target.imagePath,
  };
}

/**
 * 그 메시지가 다시 만든 답의 이전 버전이면 그 버전 묶음의 첫 번호와 자리다. 버전이 하나뿐이거나 찾지 못하면 null 이다
 * (설계 chat-reference DECISION19). 묶음은 `message-versions.ts` 처럼 `replacesMessageId` 로 잇는다.
 */
export function versionOf(
  messages: { id: number | string; replacesMessageId?: number | null }[],
  messageId: number,
): { slotId: number; index: number } | null {
  const byId = new Map(messages.map((message) => [message.id, message]));
  let slotId = messageId;
  for (
    let current = byId.get(messageId);
    current?.replacesMessageId != null;
    current = byId.get(current.replacesMessageId)
  )
    slotId = current.replacesMessageId;
  const chain: number[] = [slotId];
  for (let next = true; next;) {
    const last = chain[chain.length - 1];
    const following = messages.find(
      (message) => message.replacesMessageId === last,
    );
    next = typeof following?.id === "number";
    if (next) chain.push(following!.id as number);
  }
  return chain.length > 1 ? { slotId, index: chain.indexOf(messageId) } : null;
}
