import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

export async function POST(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "실행 번호가 올바르지 않습니다." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/chat/executions/${id}/stop`, { method: "POST" });
  return result.ok
    ? NextResponse.json(result.data, { status: 202 })
    : NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
}
