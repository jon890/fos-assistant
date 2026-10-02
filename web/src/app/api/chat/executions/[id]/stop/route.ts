import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

export async function POST(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id)) {
    return errorResponse("VALIDATION_FAILED", "실행 번호가 올바르지 않아요.", 400);
  }
  const result = await callControlPlane(`/api/v1/chat/executions/${id}/stop`, { method: "POST" });
  return result.ok
    ? NextResponse.json(result.data, { status: 202 })
    : errorResponse(result.code, result.message, result.status);
}
