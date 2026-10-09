import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ path: string[] }> };

/**
 * Control Plane 이 준 응답에서 옮기는 머리글이다. 이 밖의 머리글은 옮기지 않는다.
 *
 * <p>`Content-Security-Policy` 의 `sandbox` 가 주소를 직접 열었을 때도 스크립트를 막는다(ADR-027).
 * Control Plane 이 붙이는 `X-Frame-Options: DENY` 를 옮기면 같은 출처 iframe 에서도 HTML 미리보기가 뜨지 않는다.
 * `Content-Disposition` 은 미리보기(`inline`)와 내려받기(`attachment`)를 가르고 파일 이름을 싣는다.
 */
const FORWARDED_HEADERS = [
  "content-type",
  "content-disposition",
  "content-security-policy",
  "x-content-type-options",
  "cache-control",
  "content-length",
];

function badRequest() {
  return errorResponse(
    "VALIDATION_FAILED",
    "파일 경로가 올바르지 않아요.",
    400,
  );
}

/**
 * 실행 공간 안의 파일 본문을 그대로 흘려보낸다. 주인과 경로 규칙은 Control Plane 이 한 번 더 판정한다.
 *
 * <p>요청 인자는 `download=1` 만 옮긴다. 그 밖의 인자와 요청 머리글은 옮기지 않는다.
 */
export async function GET(request: Request, context: RouteContext) {
  const { path } = await context.params;
  if (
    path.length === 0 ||
    path.some((segment) => segment === "" || segment === "..")
  )
    return badRequest();

  const download =
    new URL(request.url).searchParams.get("download") === "1"
      ? "?download=1"
      : "";
  const opened = await forwardControlPlane(
    `/api/v1/workspace/files/${path.map(encodeURIComponent).join("/")}${download}`,
    { method: "GET" },
  );
  if (!opened.ok) {
    return errorResponse(opened.code, opened.message, opened.status);
  }

  const upstream = opened.response;
  if (!upstream.ok || !upstream.body) {
    const payload = await upstream.json().catch(() => ({
      code: "INTERNAL_ERROR",
      message: "파일을 불러오지 못했어요.",
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
