import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { isConversationId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type SkillDelegationResponse = {
  conversationId: string;
  executionId: number;
};

/** `/` 목록에서 고른 전문 에이전트의 스킬을 맡긴다(docs/features/assistant-hub/spec.md 의 REQ10). 결과는 대화에 따로 온다. */
export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body as {
    conversationId?: string | null;
    agentCode?: string;
    text?: string;
  };
  if (!body.text || body.text.trim().length === 0 || !body.agentCode) {
    return errorResponse("VALIDATION_FAILED", "맡길 스킬을 골라 주세요.", 400);
  }
  if (body.conversationId != null && !isConversationId(body.conversationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 주소가 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane<SkillDelegationResponse>(
    "/api/v1/chat/skill-delegations",
    {
      method: "POST",
      body: {
        conversationId: body.conversationId ?? null,
        agentCode: body.agentCode,
        text: body.text,
      },
    },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data, { status: 202 });
}
