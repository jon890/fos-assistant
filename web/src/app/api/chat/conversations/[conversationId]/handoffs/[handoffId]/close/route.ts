import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import {
  connectorActionResponse,
  invalidConnectorActionRequest,
  isActionId,
} from "@/lib/connector-action-route";
import { isConversationId } from "@/lib/conversation-id";
import { readJsonBody } from "@/lib/json-body";
import { safeHandoffClose } from "@/lib/handoff";

type Context = {
  params: Promise<{ conversationId: string; handoffId: string }>;
};

/**
 * 넘기기 카드 하나를 닫는다. 넘기기 번호는 그 호출의 판정 줄 번호다. 본문은 `{"outcome": "DONE" | "STOP"}` 만 받아 그대로 넘긴다.
 * 답은 `docs/features/mobile-default-chat/design.md` 의 「단추 닫기 API」 가 갖는다. 모양이 틀린 답은 502 다.
 */
export async function POST(request: Request, { params }: Context) {
  const { conversationId, handoffId } = await params;
  const read = await readJsonBody(request);
  if (!read.ok) return read.response;
  const outcome = read.body.outcome;
  if (
    !isConversationId(conversationId) ||
    !isActionId(handoffId) ||
    (outcome !== "DONE" && outcome !== "STOP")
  )
    return invalidConnectorActionRequest();
  const failed = () =>
    connectorActionResponse({
      ok: false,
      status: 502,
      code: "INTERNAL_ERROR",
      message: "",
    });
  try {
    const result = await callControlPlane<unknown>(
      `/api/v1/chat/conversations/${conversationId}/handoffs/${handoffId}/close`,
      { method: "POST", body: { outcome } },
    );
    if (!result.ok) return connectorActionResponse(result);
    const closed = safeHandoffClose(result.data);
    return closed === null ? failed() : NextResponse.json({ result: closed });
  } catch {
    return failed();
  }
}
