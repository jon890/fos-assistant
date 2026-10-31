import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";
import { readJsonBody } from "@/lib/json-body";

type Context = { params: Promise<{ conversationId: string }> };

/**
 * 「참고한 기억」 항목의 「관련 없음」, 「틀림」 단추를 남긴다. 본문의 `executionId`, `memoryId`, `reaction` 만 넘기고,
 * 성공하면 본문 없이 204 다. 기억 내용은 바뀌지 않는다.
 */
export async function POST(request: Request, { params }: Context) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { conversationId } = await params;
  if (!isConversationId(conversationId))
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 번호가 올바르지 않아요.",
      400,
    );
  const { executionId, memoryId, reaction } = parsed.body;
  const result = await callControlPlane(
    `/api/v1/chat/conversations/${conversationId}/memory-uses/reactions`,
    { method: "POST", body: { executionId, memoryId, reaction } },
  );
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : errorResponse(result.code, result.message, result.status);
}
