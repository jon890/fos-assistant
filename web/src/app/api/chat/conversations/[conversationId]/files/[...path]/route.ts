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
const FORWARDED_HEADERS = ["content-type", "content-security-policy", "x-content-type-options", "cache-control"];

function badRequest() {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "대화 주소나 파일 경로가 올바르지 않습니다." },
    { status: 400 },
  );
}

/** 대화 결과물 폴더 안의 파일 본문을 그대로 흘려보낸다. 폴더 밖인지는 Control Plane 이 한 번 더 판정한다. */
export async function GET(_request: Request, context: RouteContext) {
  const { conversationId, path } = await context.params;
  if (!isConversationId(conversationId)) return badRequest();
  if (path.length === 0 || path.some((segment) => segment === "" || segment === "..")) return badRequest();

  const opened = await forwardControlPlane(
    `/api/v1/chat/conversations/${conversationId}/files/${path.map(encodeURIComponent).join("/")}`,
    { method: "GET" },
  );
  if (!opened.ok) {
    return NextResponse.json({ code: opened.code, message: opened.message }, { status: opened.status });
  }

  const upstream = opened.response;
  if (!upstream.ok || !upstream.body) {
    const payload = await upstream.json().catch(() => ({
      code: "INTERNAL_ERROR",
      message: "파일을 읽지 못했습니다.",
    }));
    return NextResponse.json(payload, { status: upstream.status });
  }

  const headers = new Headers();
  for (const name of FORWARDED_HEADERS) {
    const value = upstream.headers.get(name);
    if (value !== null) headers.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers });
}
