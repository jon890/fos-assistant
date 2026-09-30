import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = {
  params: Promise<{ conversationId: string; path: string[] }>;
};

/**
 * Control Plane 이 준 응답에서 옮기는 머리글이다. 이 밖의 머리글은 옮기지 않는다.
 *
 * <p>`Content-Security-Policy` 의 `sandbox` 가 주소를 직접 열었을 때도 스크립트를 막는다(ADR-027).
 * Control Plane 이 붙이는 `X-Frame-Options: DENY` 를 옮기면 같은 출처 iframe 에서도 뜨지 않는다.
 */
const FORWARDED_HEADERS = [
  "content-type",
  "content-security-policy",
  "x-content-type-options",
  "cache-control",
  "etag",
  "last-modified",
];

/** 브라우저가 보낸 요청 머리글 가운데 Control Plane 으로 옮기는 것이다. 쿠키 같은 나머지는 옮기지 않는다. */
const CONDITIONAL_REQUEST_HEADERS = ["if-none-match", "if-modified-since"];

function badRequest() {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "대화 주소나 파일 경로가 올바르지 않아요." },
    { status: 400 },
  );
}

/**
 * 대화 결과물 폴더 안의 파일 본문을 그대로 흘려보낸다. 폴더 밖인지는 Control Plane 이 한 번 더 판정한다.
 *
 * <p>조건부 요청 머리글(`If-None-Match`, `If-Modified-Since`)을 옮기고, Control Plane 이 304 로 답하면 본문
 * 없이 그대로 돌려준다.
 */
export async function GET(request: Request, context: RouteContext) {
  const { conversationId, path } = await context.params;
  if (!isConversationId(conversationId)) return badRequest();
  if (path.length === 0 || path.some((segment) => segment === "" || segment === "..")) return badRequest();

  const conditional: Record<string, string> = {};
  for (const name of CONDITIONAL_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) conditional[name] = value;
  }

  const opened = await forwardControlPlane(
    `/api/v1/chat/conversations/${conversationId}/files/${path.map(encodeURIComponent).join("/")}`,
    { method: "GET", headers: conditional },
  );
  if (!opened.ok) {
    return NextResponse.json({ code: opened.code, message: opened.message }, { status: opened.status });
  }

  const upstream = opened.response;
  const headers = new Headers();
  for (const name of FORWARDED_HEADERS) {
    const value = upstream.headers.get(name);
    if (value !== null) headers.set(name, value);
  }
  // 304 는 오류가 아니다. 본문 없이 옮긴 머리글만 돌려주면 브라우저가 가진 사본을 쓴다.
  if (upstream.status === 304) return new Response(null, { status: 304, headers });

  if (!upstream.ok || !upstream.body) {
    const payload = await upstream.json().catch(() => ({
      code: "INTERNAL_ERROR",
      message: "파일을 불러오지 못했어요.",
    }));
    return NextResponse.json(payload, { status: upstream.status });
  }

  return new Response(upstream.body, { status: upstream.status, headers });
}
