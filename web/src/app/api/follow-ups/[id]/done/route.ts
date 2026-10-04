import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { isPublicId } from "@/lib/conversation-id";

/** 열린 할 일을 끝낸다. 본문이 없다. */
export async function POST(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!isPublicId(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "할 일 식별자가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(`/api/v1/follow-ups/${id}/done`, {
    method: "POST",
  });
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
