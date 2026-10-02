import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { isConversationId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type SendMessageResponse = {
  conversationId: string;
  executionId: number;
  assistantText: string;
};

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body as {
    conversationId?: string;
    text?: string;
    agentCode?: string;
    attachmentIds?: number[];
  };
  if (!body.text || body.text.trim().length === 0) {
    return errorResponse(
      "VALIDATION_FAILED",
      "보낼 내용을 입력해 주세요.",
      400,
    );
  }
  if (body.conversationId != null && !isConversationId(body.conversationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 주소가 올바르지 않아요.",
      400,
    );
  }

  const result = await callControlPlane<SendMessageResponse>(
    "/api/v1/chat/messages",
    {
      method: "POST",
      body: {
        conversationId: body.conversationId ?? null,
        text: body.text,
        agentCode: body.agentCode ?? null,
        attachmentIds: body.attachmentIds ?? [],
      },
    },
  );

  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
