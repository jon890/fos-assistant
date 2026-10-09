/** 도구가 내는 오류 코드. connector.json 의 `errors` 표와 같은 이름이다. */
export type ErrorCode =
  | "NAVER_BLOG_INVALID_INPUT"
  | "NAVER_BLOG_PHOTO_INVALID"
  | "NAVER_BLOG_LOGIN_REQUIRED"
  | "NAVER_BLOG_BROWSER_UNREACHABLE"
  | "NAVER_BLOG_BUSY"
  | "NAVER_BLOG_JOB_NOT_FOUND"
  | "NAVER_BLOG_DRAFT_NOT_FOUND"
  | "NAVER_BLOG_EDITOR_IN_USE"
  | "NAVER_BLOG_START_UNKNOWN"
  | "NAVER_BLOG_UNAVAILABLE";

/** 코드만 싣는 오류. CDP 주소, 파일 경로, 브라우저가 준 원문을 담지 않는다. */
export class ToolError extends Error {
  constructor(readonly code: ErrorCode) {
    super(code);
  }
}

/** 알 수 없는 예외는 원문을 버리고 `NAVER_BLOG_UNAVAILABLE` 로 바꾼다. */
export function toolFailure(error: unknown) {
  const code =
    error instanceof ToolError ? error.code : "NAVER_BLOG_UNAVAILABLE";
  return {
    content: [
      { type: "text" as const, text: JSON.stringify({ error: { code } }) },
    ],
    isError: true,
  };
}

export function toolSuccess(value: unknown) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(value) }],
  };
}

/** 도구 처리 함수를 감싸 결과나 오류를 MCP 결과 모양으로 바꾼다. */
export async function guard(work: () => Promise<unknown>) {
  try {
    return toolSuccess(await work());
  } catch (error) {
    return toolFailure(error);
  }
}
