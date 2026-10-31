import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

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

/** 보관본 본문을 흘려보낸다. 주인은 Control Plane 이 한 번 더 판정한다. `download=1` 만 옮긴다. */
export async function GET(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "보관한 결과물 번호가 올바르지 않아요.",
      400,
    );
  const download =
    new URL(request.url).searchParams.get("download") === "1"
      ? "?download=1"
      : "";
  const opened = await forwardControlPlane(
    `/api/v1/artifact-vault/${id}/content${download}`,
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
