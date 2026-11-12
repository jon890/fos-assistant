/**
 * 대화 목록의 서버 라우트를 부른다. 응답을 읽고 실패를 문구로 바꾸는 일은 부르는 쪽이 한다.
 */

/** 목록의 한 쪽을 읽는다. 첫 쪽이면 `cursor` 가 null 이다. */
export function requestConversationPage(
  cursor: string | null,
  limit: number,
): Promise<Response> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor !== null) query.set("cursor", cursor);
  return fetch(`/api/chat/conversations?${query}`, { cache: "no-store" });
}

/** 대화 한 줄을 읽는다. 첫 쪽에 없는 오래된 대화를 열 때 쓴다. */
export function requestConversation(id: string): Promise<Response> {
  return fetch(`/api/chat/conversations/${id}`, { cache: "no-store" });
}

/** 요청자의 대화방 한 줄을 읽는다. 목록이 대화방을 빼므로 사이드바가 따로 읽는다. 아직 열지 않았으면 404 다. */
export function requestRoom(): Promise<Response> {
  return fetch("/api/chat/room", { cache: "no-store" });
}
