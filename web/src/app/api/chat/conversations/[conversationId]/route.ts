import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

type RouteContext = { params: Promise<{ conversationId: string }> };

async function idOf(context: RouteContext): Promise<number | null> {
  const { conversationId } = await context.params;
  return /^\d+$/.test(conversationId) ? Number(conversationId) : null;
}

function invalid() {
  return NextResponse.json({ code: "VALIDATION_FAILED", message: "대화 번호가 올바르지 않습니다." }, { status: 400 });
}

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok) return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  return result.data === null ? new NextResponse(null, { status: result.status }) : NextResponse.json(result.data);
}

export async function PATCH(request: Request, context: RouteContext) {
  const id = await idOf(context);
  return id === null ? invalid() : response(await callControlPlane(`/api/v1/chat/conversations/${id}`, {
    method: "PATCH", body: await request.json(),
  }));
}

export async function DELETE(_request: Request, context: RouteContext) {
  const id = await idOf(context);
  return id === null ? invalid() : response(await callControlPlane(`/api/v1/chat/conversations/${id}`, {
    method: "DELETE",
  }));
}
