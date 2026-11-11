import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import {
  connectorActionResponse,
  invalidConnectorActionRequest,
} from "@/lib/connector-action-route";
import { isConversationId } from "@/lib/conversation-id";
import { safeHandoff, type Handoff } from "@/lib/handoff";

type Context = { params: Promise<{ conversationId: string }> };

/** 그 대화의 열린 화면 넘기기를 읽는다. 대화의 넘기기 카드는 이 응답으로만 그린다. 모양이 틀린 줄은 버린다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId } = await params;
  if (!isConversationId(conversationId)) return invalidConnectorActionRequest();
  try {
    const result = await callControlPlane<unknown>(
      `/api/v1/chat/conversations/${conversationId}/handoffs`,
    );
    if (!result.ok) return connectorActionResponse(result);
    const rows = Array.isArray(result.data) ? result.data : [];
    return NextResponse.json(
      rows.map(safeHandoff).filter((row): row is Handoff => row !== null),
    );
  } catch {
    return connectorActionResponse({
      ok: false,
      status: 502,
      code: "INTERNAL_ERROR",
      message: "",
    });
  }
}
