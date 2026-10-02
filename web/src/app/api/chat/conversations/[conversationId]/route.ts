import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = { params: Promise<{ conversationId: string }> };

async function idOf(context: RouteContext): Promise<string | null> {
  const { conversationId } = await context.params;
  return isConversationId(conversationId) ? conversationId : null;
}

function invalid() {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "대화 주소가 올바르지 않아요." },
    { status: 400 },
  );
}

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok)
    return NextResponse.json(
      { code: result.code, message: result.message },
      { status: result.status },
    );
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}

export async function PATCH(request: Request, context: RouteContext) {
  const id = await idOf(context);
  if (id === null) return invalid();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return response(
    await callControlPlane(`/api/v1/chat/conversations/${id}`, {
      method: "PATCH",
      body: parsed.body,
    }),
  );
}

export async function DELETE(_request: Request, context: RouteContext) {
  const id = await idOf(context);
  return id === null
    ? invalid()
    : response(
        await callControlPlane(`/api/v1/chat/conversations/${id}`, {
          method: "DELETE",
        }),
      );
}
