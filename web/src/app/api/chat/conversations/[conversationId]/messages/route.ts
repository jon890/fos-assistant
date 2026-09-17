import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

export async function GET(_request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!/^\d+$/.test(conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 번호가 올바르지 않습니다." },
      { status: 400 },
    );
  }

  const result = await callControlPlane<unknown[]>(
    `/api/v1/chat/conversations/${conversationId}/messages`,
  );
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
