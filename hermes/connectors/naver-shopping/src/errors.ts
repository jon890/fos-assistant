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

/**
 * 사람에게 넘기는 단계와 남긴 탭이다. fos-ctx hook 이 이 칸을 보고 Control Plane 에 화면 넘기기를 알린다.
 * 모양은 docs/features/naver-shopping/design.md 의 「플러그인 결과(공통 계약)」 가 갖는다.
 */
export type Handoff = {
  need: "login" | "captcha" | "device_check" | "unexpected_page";
  tab: string;
};

/** 코드만 싣는 오류. 중계 주소, 검색어, 화면의 글, 브라우저가 준 원문을 담지 않는다. 넘기기 칸은 어휘와 탭 id 뿐이다. */
export class ToolError extends Error {
  constructor(
    readonly code: ErrorCode,
    readonly handoff: Handoff | null = null,
  ) {
    super(code);
  }
}

/**
 * 모델이 화면 넘기기를 성공으로 읽지 않게 하는 안내다. 고정 글이고 화면 글이나 주소를 담지 않는다.
 * 사람이 푼 뒤에 이어 가는 일은 스킬과 카드의 「다 했어요」 가 맡는다.
 */
const HANDOFF_MESSAGE =
  "사람이 해야 하는 화면에서 멈췄다. 요청한 일은 아직 끝나지 않았고 결과가 없다. " +
  "사용자에게 「내 브라우저」 에서 그 화면을 직접 마쳐 달라고 안내하고, 사용자가 마쳤다고 하기 전에는 같은 도구를 다시 부르지 않는다.";

/**
 * 화면 넘기기 결과다. `isError` 를 싣지 않는다.
 * Hermes 는 같은 서버의 `isError` 결과가 연속 3회면 60초 동안 그 서버의 호출을 막는다. 사람이 풀고 돌아와도 막혀 있으므로,
 * 사람이 해야 하는 화면은 실패가 아닌 결과로 돌려준다. 성공으로 읽히지 않게 `status` 와 `message` 로 멈췄다고 밝힌다.
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

/** 알 수 없는 예외는 원문을 버리고 `NAVER_SHOPPING_UNAVAILABLE` 로 바꾼다. 화면 넘기기는 오류가 아닌 결과다. */
export function toolFailure(error: unknown) {
  if (error instanceof ToolError && error.handoff)
    return handoffResult(error.code, error.handoff);
  const code =
    error instanceof ToolError ? error.code : "NAVER_SHOPPING_UNAVAILABLE";
  return {
    content: [
      {
        type: "text" as const,
        text: JSON.stringify({ error: { code } }),
      },
    ],
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

/** 진단 줄의 칸 이름과 단계. 영문 소문자, 숫자, `_`, `.` 만 받는다. 주소와 화면 글이 섞이지 않게 하는 마지막 관문이다. */
const DIAGNOSTIC_WORD = /^[a-z0-9_.]{1,60}$/;

/**
 * 화면을 읽지 못했을 때 표준 오류에 한 줄 JSON 을 남긴다. 모델에게 가는 오류는 코드만 싣는다.
 * 도구 이름, 단계, 찾지 못한 칸 이름만 싣고 주소, 검색어, 화면 글은 싣지 않는다.
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
    process.stderr.write(
      `${JSON.stringify({ naver_shopping_diagnostic: entry })}\n`,
    );
  } catch {
    // 진단을 남기지 못해도 도구 결과는 그대로 돌려준다.
  }
}
