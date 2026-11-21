import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { PUBLIC_ID } from "@/lib/quality-api";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 같은 기능 묶음으로 확인을 다시 시작한다. 이미 도는 확인이 있으면 그 줄이 200 으로 온다. */
export async function POST(
  request: Request,
  context: { params: Promise<{ publicId: string }> },
) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const { publicId } = await context.params;
  if (!PUBLIC_ID.test(publicId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "확인 번호 형식이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(
    `/api/v1/admin/quality/checks/${publicId}/retry`,
    { method: "POST" },
  );
  return result.ok
    ? NextResponse.json(result.data, { status: result.status })
    : errorResponse(result.code, result.message, result.status);
}
