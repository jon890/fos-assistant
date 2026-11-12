/** 새 대화 화면의 주소다. `/` 는 기본 비서와 이어지는 대화방을 연다(docs/features/agent-team.md 의 「대화방」). */
export const NEW_CONVERSATION_PATH = "/new";

/**
 * 대화방이 날이 바뀐 첫 메시지에서 앞 맥락을 요약했을 때 남기는 알림 줄의 첫 줄이다.
 *
 * <p>Control Plane 의 `RoomContext.NOTICE_TITLE` 과 같은 글이다. `test/repo/room-notice.test.ts` 가 두 글자를 대조한다.
 */
export const ROOM_SUMMARY_TITLE = "어제까지 이야기를 요약해 이어요";

/** 알림 줄이 대화방의 요약 줄이면 요약 글을, 아니면 null 을 돌려준다. 요약 줄은 첫 줄, 빈 줄, 요약 차례다. */
export function roomSummaryOf(content: string): string | null {
  const head = `${ROOM_SUMMARY_TITLE}\n\n`;
  return content.startsWith(head) ? content.slice(head.length) : null;
}
