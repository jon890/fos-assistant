import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 그 브라우저를 끄고 프로필까지 지운다. */
export async function DELETE(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^[1-9]\d{0,18}$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "브라우저 주소가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(`/api/v1/admin/browsers/${id}`, {
    method: "DELETE",
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}
