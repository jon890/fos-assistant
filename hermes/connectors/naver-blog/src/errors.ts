import { filterPopupDiagnostics } from "./editor/popup.ts";

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
  | "NAVER_BLOG_EDITOR_CHANGED"
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

/** 진단 줄에 싣는 클래스 이름 하나. 태그와 앞 클래스 둘을 이은 모양(`div.layer_x.is_show`)만 받는다. */
const CLASS_NAME = /^[A-Za-z0-9_.-]{1,80}$/;
/** 진단 줄에 싣는 칸. 그 밖의 칸은 버린다. */
const DIAGNOSIS_FIELDS = [
  "publish_button",
  "covered_by",
  "category_button",
  "category_covered_by",
] as const;
const DIAGNOSIS_LAYERS = 8;
const MESSAGE_MAX = 100;

/** 클래스 이름 모양이 아닌 값은 버린다. 주소와 블로그 아이디, 글이 이 줄로 새지 않게 하는 마지막 관문이다. */
function classOnly(value: unknown) {
  return typeof value === "string" && CLASS_NAME.test(value) ? value : null;
}

function safeDiagnosis(raw: unknown) {
  if (typeof raw !== "object" || raw === null) return undefined;
  const source = raw as Record<string, unknown>;
  const safe: Record<string, string | null | string[]> = {};
  for (const field of DIAGNOSIS_FIELDS)
    if (field in source) safe[field] = classOnly(source[field]);
  if (Array.isArray(source.layers))
    safe.layers = source.layers
      .map(classOnly)
      .filter((name): name is string => name !== null)
      .slice(0, DIAGNOSIS_LAYERS);
  return safe;
}

/**
 * 편집기 단계의 실패를 표준 오류에 한 줄 JSON 으로 남긴다. 모델에게 가는 오류에는 코드만 실려, 어느 단계에서 막혔는지는 이 줄로만 알 수 있다.
 * 도구 이름, 편집기 오류 코드, 단계, 고정 문구, 화면 진단(클래스 이름), 알림 진단(종류, 상자 클래스, 알려진 단추 이름, 닫아 보았는지)만 싣는다.
 * 주소, 블로그 아이디, 글, 브라우저가 준 원문, 파일 경로는 싣지 않는다. 문구에 `/`, `@`, `\` 가 있거나 100자를 넘으면 문구를 버린다.
 * 편집기 오류가 아닌 예외는 클래스 이름만 남긴다.
 */
export function logToolDiagnostic(
  tool: string,
  error: {
    code?: string;
    stage?: string;
    message?: string;
    extra?: Record<string, unknown>;
    name?: string;
  },
  editor: boolean,
) {
  const entry: Record<string, unknown> = { tool };
  if (editor) {
    entry.editor_code = classOnly(error.code);
    entry.stage = classOnly(error.stage);
    const message = String(error.message ?? "");
    entry.message =
      message.length <= MESSAGE_MAX && !/[/@\\]/.test(message) ? message : null;
    const diagnosis = safeDiagnosis(error.extra?.diagnosis);
    if (diagnosis) entry.diagnosis = diagnosis;
    // 화면을 덮은 알림의 종류와 단추 이름. 알려진 이름만 싣고 알림의 글은 싣지 않는다.
    const popup = filterPopupDiagnostics(error.extra?.popup_diagnostics);
    if (popup) entry.popup_diagnostics = popup;
  } else {
    entry.error_class = classOnly(error.name);
  }
  try {
    process.stderr.write(
      `${JSON.stringify({ naver_blog_diagnostic: entry })}\n`,
    );
  } catch {
    // 진단을 남기지 못해도 도구 결과는 그대로 돌려준다.
  }
}
