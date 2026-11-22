import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { PUBLIC_ID } from "@/lib/quality-api";

/** 관리자. 확인 한 줄과 기능별 결과다. */
export async function GET(
  _request: Request,
  context: { params: Promise<{ publicId: string }> },
) {
  const { publicId } = await context.params;
  if (!PUBLIC_ID.test(publicId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "확인 번호 형식이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(
    `/api/v1/admin/quality/checks/${publicId}`,
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
