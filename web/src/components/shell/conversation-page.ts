import type { Conversation } from "./conversations-provider";

/** 서버가 돌려주는 대화 목록의 한 쪽이다. */
export type ConversationPage = {
  items: Conversation[];
  nextCursor: string | null;
};

/**
 * 새로 읽은 첫 쪽을 이미 읽어 둔 목록에 합친다.
 *
 * <p>첫 쪽 뒤에 이어 읽어 둔 줄은 그대로 두고, 첫 쪽에 다시 든 줄과 겹치는 것만 뺀다. 그래야 새로 고칠 때마다 스크롤로
 * 불러 둔 줄이 사라지지 않는다. 이어 읽을 자리는 이어 읽은 줄이 남아 있으면 지금 것을 그대로 쓴다.
 */
export function mergeFirstPage(
  fresh: ConversationPage,
  current: ConversationPage,
): ConversationPage {
  const boundary = fresh.items.at(-1);
  if (fresh.nextCursor === null || boundary === undefined) return fresh;
  const freshIds = new Set(fresh.items.map((item) => item.id));
  const limit = Date.parse(boundary.updatedAt);
  const tail = current.items.filter(
    (item) => !freshIds.has(item.id) && Date.parse(item.updatedAt) <= limit,
  );
  if (tail.length === 0) return fresh;
  return {
    items: [...fresh.items, ...tail],
    nextCursor: current.nextCursor,
  };
}
