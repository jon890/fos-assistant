import { isConversationId } from "@/lib/conversation-id";
import { readJsonBody } from "@/lib/json-body";
import { forwardPending, invalidPendingRequest } from "@/lib/pending-route";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 이 대화에 쌓인 대기 메시지와 멈춰 두었는지를 읽는다. */
export async function GET(_request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) return invalidPendingRequest();
  return forwardPending(
    `/api/v1/chat/conversations/${conversationId}/pending`,
    { method: "GET" },
  );
}

/** 답이 오는 동안 보낸 글을 대기 메시지로 더한다. 글만 넘긴다. */
export async function POST(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) return invalidPendingRequest();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return forwardPending(
    `/api/v1/chat/conversations/${conversationId}/pending`,
    { method: "POST", body: { text: parsed.body.text } },
  );
}
