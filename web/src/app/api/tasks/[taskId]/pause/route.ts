import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isPublicId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ taskId: string }> };

export async function POST(_request: Request, context: RouteContext) {
  const { taskId } = await context.params;
  if (!isPublicId(taskId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "작업 주소가 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane<unknown>(
    `/api/v1/tasks/${taskId}/pause`,
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
