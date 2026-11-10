/** 도구가 내는 오류 코드. assistant.json 의 `errors` 표와 같은 이름이다. */
export type ErrorCode =
  | "NAVER_SHOPPING_INVALID_INPUT"
  | "NAVER_SHOPPING_BROWSER_UNREACHABLE"
  | "NAVER_SHOPPING_LOGIN_REQUIRED"
  | "NAVER_SHOPPING_CAPTCHA"
  | "NAVER_SHOPPING_DEVICE_CHECK"
  | "NAVER_SHOPPING_NOT_FOUND"
  | "NAVER_SHOPPING_PAGE_CHANGED"
  | "NAVER_SHOPPING_UNAVAILABLE";

/** 코드만 싣는 오류. 중계 주소, 검색어, 화면의 글, 브라우저가 준 원문을 담지 않는다. */
export class ToolError extends Error {
  constructor(readonly code: ErrorCode) {
    super(code);
  }
}

/** 알 수 없는 예외는 원문을 버리고 `NAVER_SHOPPING_UNAVAILABLE` 로 바꾼다. */
export function toolFailure(error: unknown) {
  const code = error instanceof ToolError ? error.code : "NAVER_SHOPPING_UNAVAILABLE";
  return {
    content: [{ type: "text" as const, text: JSON.stringify({ error: { code } }) }],
    isError: true,
  };
}

/** 도구 처리 함수를 감싸 결과나 오류를 MCP 결과 모양으로 바꾼다. */
export async function guard(work: () => Promise<unknown>) {
  try {
    return { content: [{ type: "text" as const, text: JSON.stringify(await work()) }] };
  } catch (error) {
    return toolFailure(error);
  }
}

/** 진단 줄의 칸 이름과 단계. 영문 소문자, 숫자, `_`, `.` 만 받는다. 주소와 화면 글이 섞이지 않게 하는 마지막 관문이다. */
const DIAGNOSTIC_WORD = /^[a-z0-9_.]{1,60}$/;

/**
 * 화면을 읽지 못했을 때 표준 오류에 한 줄 JSON 을 남긴다. 모델에게 가는 오류는 코드만 싣는다.
 * 도구 이름, 단계, 찾지 못한 칸 이름만 싣고 주소, 검색어, 화면 글은 싣지 않는다.
 */
export function logDiagnostic(tool: string, stage: string, missing: string[] = []) {
  const safe = (value: string) => (DIAGNOSTIC_WORD.test(value) ? value : null);
  const entry = { tool: safe(tool), stage: safe(stage), missing: missing.map(safe).filter(Boolean).slice(0, 10) };
  try {
    process.stderr.write(`${JSON.stringify({ naver_shopping_diagnostic: entry })}\n`);
  } catch {
    // 진단을 남기지 못해도 도구 결과는 그대로 돌려준다.
  }
}
