import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

/** 실행 기록 페이지 요청에서 허용한 조회 값만 Control Plane 에 넘긴다. */
export async function GET(request: Request) {
  const asked = new URL(request.url).searchParams;
  const query = new URLSearchParams({ limit: asked.get("limit") ?? "50" });
  const cursor = asked.get("cursor");
  if (cursor !== null) query.set("cursor", cursor);

  const result = await callControlPlane<unknown>(
    `/api/v1/usage/executions/page?${query.toString()}`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
