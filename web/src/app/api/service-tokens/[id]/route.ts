import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

export async function DELETE(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "토큰 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/service-tokens/${Number(id)}`,
    { method: "DELETE" },
  );
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}
