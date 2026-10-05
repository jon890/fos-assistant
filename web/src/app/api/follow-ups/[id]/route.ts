import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";
import { isPublicId } from "@/lib/conversation-id";

/** 할 일의 제목, 기한, 기다리는 중을 고친다. 본문에 없는 칸은 그대로 두고 `dueAt: null` 은 기한을 지운다. */
export async function PATCH(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!isPublicId(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "할 일 식별자가 올바르지 않아요.",
      400,
    );
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(`/api/v1/follow-ups/${id}`, {
    method: "PATCH",
    body: parsed.body,
  });
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
