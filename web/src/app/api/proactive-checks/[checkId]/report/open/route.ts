import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

export async function POST(
  _request: Request,
  context: { params: Promise<{ checkId: string }> },
) {
  const { checkId } = await context.params;
  if (!/^\d+$/.test(checkId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "보고 번호가 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(
    `/api/v1/proactive-checks/${checkId}/report/open`,
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return new NextResponse(null, { status: result.status });
}
