import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

export async function POST(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id)) return NextResponse.json({ code: "VALIDATION_FAILED", message: "기억 번호가 올바르지 않아요." }, { status: 400 });
  const result = await callControlPlane(`/api/v1/memories/${id}/accept`, { method: "POST" });
  return result.ok ? NextResponse.json(result.data) : NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
}
