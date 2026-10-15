/** 실패 사유 코드를 사용자 문구로 옮긴다. 모르는 코드는 덧붙이지 않는다. */
export const FAILURE_REASONS: Record<string, string> = {
  screen_blocked: "글쓰기 화면에 알림이 떠 있었어요.",
  title_cursor_covered:
    "글쓰기 화면의 다른 요소가 제목 자리를 가리고 있었어요.",
  body_cursor_covered: "글쓰기 화면의 다른 요소가 본문 자리를 가리고 있었어요.",
  title_cursor_failed: "글쓰기 화면의 제목 자리에 글을 넣지 못했어요.",
  body_cursor_failed: "글쓰기 화면의 본문 자리에 글을 넣지 못했어요.",
  title_mismatch: "글쓰기 화면에 제목이 맞게 들어가지 않았어요.",
  body_mismatch: "글쓰기 화면에 본문이 맞게 들어가지 않았어요.",
};
