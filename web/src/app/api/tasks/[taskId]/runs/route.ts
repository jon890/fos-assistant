import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isPublicId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ taskId: string }> };

/** 작업의 최근 실행이다. `limit` 만 Control Plane 에 넘긴다. */
export async function GET(request: Request, context: RouteContext) {
  const { taskId } = await context.params;
  if (!isPublicId(taskId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "작업 주소가 올바르지 않아요.",
      400,
    );
  }
  const limit = new URL(request.url).searchParams.get("limit");
  const suffix = limit === null ? "" : `?limit=${encodeURIComponent(limit)}`;
  const result = await callControlPlane<unknown>(
    `/api/v1/tasks/${taskId}/runs${suffix}`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
