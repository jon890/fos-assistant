import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

/** 판단 provider 는 브라우저가 고르지 못한다. 요청 본문을 읽지 않고 설치된 adapter 이름을 직접 보낸다. */
const PROVIDER = "hermes";

/** 관리자가 자기 살펴보기 한 건을 평가하고 판정한다. 권한은 Control Plane 이 막는다. */
export async function POST(
  _request: Request,
  context: { params: Promise<{ checkId: string }> },
) {
  const { checkId } = await context.params;
  if (!/^\d+$/.test(checkId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "살펴보기 번호가 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane<unknown>(
    `/api/v1/admin/proactive-checks/${checkId}/value-evaluation-runs`,
    { method: "POST", body: { provider: PROVIDER } },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data, { status: result.status });
}
