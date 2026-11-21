import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

const ID = /^[1-9][0-9]*$/;

/** 관리자. 평가 전용 사용자의 실행 트리만 연다. 다른 사용자의 실행은 404 다. */
export async function GET(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!ID.test(id)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "실행 번호 형식이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(
    `/api/v1/admin/quality/executions/${id}/tree`,
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
