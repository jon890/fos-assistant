import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ id: string; path: string[] }> };

/**
 * Control Plane 이 준 응답에서 옮기는 머리글이다. 이 밖의 머리글은 옮기지 않는다.
 *
 * <p>`Content-Security-Policy` 의 `sandbox` 가 주소를 직접 열었을 때도 스크립트를 막는다(ADR-027).
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
    "보관한 결과물 번호나 파일 경로가 올바르지 않아요.",
    400,
  );
}

/**
 * 보관본 안의 파일 하나를 흘려보낸다. 묶음 HTML 미리보기가 상대 주소로 부르는 사진과 스타일이 이 길로 온다.
 *
 * <p>주인과 그 묶음에 든 파일인지는 Control Plane 이 파일마다 다시 판정한다.
 */
export async function GET(_request: Request, context: RouteContext) {
  const { id, path } = await context.params;
  if (!/^\d+$/.test(id)) return badRequest();
  if (
    path.length === 0 ||
    path.some(
      (segment) => segment === "" || segment === "." || segment === "..",
    )
  )
    return badRequest();
  const opened = await forwardControlPlane(
    `/api/v1/artifact-vault/${id}/files/${path.map(encodeURIComponent).join("/")}`,
    { method: "GET" },
  );
  if (!opened.ok)
    return errorResponse(opened.code, opened.message, opened.status);
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
