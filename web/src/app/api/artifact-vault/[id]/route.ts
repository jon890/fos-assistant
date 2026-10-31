import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 보관본 하나를 지운다. 번호가 숫자가 아니면 Control Plane 에 보내지 않는다. */
export async function DELETE(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "보관한 결과물 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(`/api/v1/artifact-vault/${id}`, {
    method: "DELETE",
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return new NextResponse(null, { status: 204 });
}
