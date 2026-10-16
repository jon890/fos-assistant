import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 기억 연결 하나를 받아들이거나 거절하거나 되돌린다. 세 라우트가 같이 쓴다. */
export async function decideMemoryLinkRoute(
  context: { params: Promise<{ id: string }> },
  action: "accept" | "reject" | "revoke",
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "연결 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/memory-links/${id}/${action}`,
    { method: "POST" },
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
