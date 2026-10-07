import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

/** 기억 기록을 되돌린다. 새로 만든 항목은 지우고 고친 항목은 고치기 전으로 돌린다. */
export async function POST(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "기억 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(`/api/v1/memory-captures/${id}/undo`, {
    method: "POST",
  });
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : errorResponse(result.code, result.message, result.status);
}
