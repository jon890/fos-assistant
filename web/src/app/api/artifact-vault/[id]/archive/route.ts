import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** Control Plane 이 준 응답에서 옮기는 머리글이다. 이 밖의 머리글은 옮기지 않는다. */
const FORWARDED_HEADERS = [
  "content-type",
  "content-disposition",
  "content-security-policy",
  "x-content-type-options",
  "cache-control",
];

/** 묶음 전체를 zip 으로 흘려보낸다. 주인은 Control Plane 이 판정하고, 풀리지 않는 파일이 있으면 쓰기 전에 거절한다. */
export async function GET(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "보관한 결과물 번호가 올바르지 않아요.",
      400,
    );
  const opened = await forwardControlPlane(
    `/api/v1/artifact-vault/${id}/archive`,
    { method: "GET" },
  );
  if (!opened.ok)
    return errorResponse(opened.code, opened.message, opened.status);
  const upstream = opened.response;
  if (!upstream.ok || !upstream.body) {
    const payload = await upstream.json().catch(() => ({
      code: "INTERNAL_ERROR",
      message: "묶음을 내려받지 못했어요.",
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
