import { NextResponse } from "next/server";

export type JsonBody =
  | { ok: true; body: Record<string, unknown> }
  | { ok: false; response: NextResponse };

/**
 * 요청 본문을 JSON 객체로 읽는다.
 *
 * <p>본문이 JSON 이 아니거나 객체가 아니면 그대로 돌려줄 400 응답을 준다. `request.json()` 을 그냥 부르면
 * 형식이 틀린 본문에 던져서 라우트가 JSON 이 아닌 500 을 낸다. 라우트는 본문을 객체로 보고 칸을 읽으므로
 * 객체가 아닌 JSON 도 모두 거절한다. `null` 이 오면 칸을 읽다가 같은 500 이 난다.
 */
export async function readJsonBody(request: Request): Promise<JsonBody> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalid();
  }
  if (body === null || typeof body !== "object" || Array.isArray(body)) return invalid();
  return { ok: true, body: body as Record<string, unknown> };
}

function invalid(): JsonBody {
  return {
    ok: false,
    response: NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." }, { status: 400 }),
  };
}
