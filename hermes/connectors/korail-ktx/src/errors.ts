/** 도구가 내는 오류 코드. assistant.json 의 `errors` 표와 같은 이름이다. */
export type ErrorCode =
  | "KORAIL_INVALID_INPUT"
  | "KORAIL_UNKNOWN_STATION"
  | "KORAIL_DATE_UNAVAILABLE"
  | "KORAIL_REJECTED"
  | "KORAIL_BROWSER_UNREACHABLE"
  | "KORAIL_LOGIN_REQUIRED"
  | "KORAIL_CAPTCHA"
  | "KORAIL_BOT_CHECK"
  | "KORAIL_PAGE_CHANGED"
  | "KORAIL_UNAVAILABLE";

/**
 * 사람에게 넘기는 단계와 남긴 탭이다. fos-ctx hook 이 이 칸을 보고 Control Plane 에 화면 넘기기를 알린다.
 * 어휘는 fos-assistant `docs/adr/ADR-20261111-connector-handoff.md` 의 「결정」 이 갖는다.
 */
export type Handoff = {
  need: "login" | "bot_check" | "captcha" | "unexpected_page";
  tab: string;
};

/**
 * 코드와, 코레일이 거절한 때만 그 글(200자까지)을 싣는 오류. 중계 주소, 역 이름, 날짜를 담지 않는다.
 * 사람에게 넘기는 화면이면 넘기기 칸(어휘와 탭 id)을 싣는다.
 */
export class ToolError extends Error {
  constructor(
    readonly code: ErrorCode,
    readonly detail: string | null = null,
    readonly handoff: Handoff | null = null,
  ) {
    super(code);
  }
}

/** 모델이 화면 넘기기를 성공으로 읽지 않게 하는 고정 안내다. 화면 글이나 주소를 담지 않는다. */
const HANDOFF_MESSAGE =
  "사람이 해야 하는 화면에서 멈췄다. 요청한 일은 아직 끝나지 않았고 결과가 없다. " +
  "사용자에게 「내 브라우저」 에서 그 화면을 직접 마쳐 달라고 안내하고, 사용자가 마쳤다고 하기 전에는 같은 도구를 다시 부르지 않는다.";

/**
 * 화면 넘기기 결과다. `isError` 를 싣지 않는다.
 * Hermes 는 같은 서버의 `isError` 결과가 연속 3회면 60초 동안 그 서버의 호출을 막아, 사람이 풀고 돌아와도 막혀 있다.
 */
export function handoffResult(code: ErrorCode, handoff: Handoff) {
  return {
    content: [
      {
        type: "text" as const,
        text: JSON.stringify({
          status: "HUMAN_ACTION_REQUIRED",
          message: HANDOFF_MESSAGE,
          handoff: { code, need: handoff.need, tab: handoff.tab },
        }),
      },
    ],
  };
}

/** 알 수 없는 예외는 원문을 버리고 `KORAIL_UNAVAILABLE` 로 바꾼다. 화면 넘기기는 오류가 아닌 결과다. */
export function toolFailure(error: unknown) {
  if (error instanceof ToolError && error.handoff)
    return handoffResult(error.code, error.handoff);
  const known = error instanceof ToolError ? error : null;
  const body = {
    code: known?.code ?? "KORAIL_UNAVAILABLE",
    ...(known?.detail ? { message: known.detail } : {}),
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify({ error: body }) }],
    isError: true,
  };
}

/** 도구 처리 함수를 감싸 결과나 오류를 MCP 결과 모양으로 바꾼다. */
export async function guard(work: () => Promise<unknown>) {
  try {
    return {
      content: [{ type: "text" as const, text: JSON.stringify(await work()) }],
    };
  } catch (error) {
    return toolFailure(error);
  }
}

/** 진단 줄의 칸 이름과 단계. 영문 소문자, 숫자, `_`, `.` 만 받는다. 역 이름과 화면 글이 섞이지 않게 하는 마지막 관문이다. */
const DIAGNOSTIC_WORD = /^[a-z0-9_.]{1,60}$/;

/**
 * 화면을 읽지 못했을 때 표준 오류에 한 줄 JSON 을 남긴다. 모델에게 가는 오류는 코드만 싣는다.
 * 도구 이름, 단계, 찾지 못한 칸 이름만 싣고 역 이름, 날짜, 화면 글은 싣지 않는다.
 */
export function logDiagnostic(
  tool: string,
  stage: string,
  missing: string[] = [],
) {
  const safe = (value: string) => (DIAGNOSTIC_WORD.test(value) ? value : null);
  const entry = {
    tool: safe(tool),
    stage: safe(stage),
    missing: missing.map(safe).filter(Boolean).slice(0, 10),
  };
  try {
    process.stderr.write(`${JSON.stringify({ korail_diagnostic: entry })}\n`);
  } catch {
    // 진단을 남기지 못해도 도구 결과는 그대로 돌려준다.
  }
}
