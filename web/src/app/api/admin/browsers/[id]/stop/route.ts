import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 그 브라우저를 끈다. 본문이 없다. */
export async function POST(
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
  const result = await callControlPlane(`/api/v1/admin/browsers/${id}/stop`, {
    method: "POST",
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}
