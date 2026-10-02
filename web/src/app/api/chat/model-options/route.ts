import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/**
 * 그 에이전트로 고를 수 있는 모델과 기본값을 Control Plane 에서 받아 온다.
 *
 * 브라우저가 보낸 것 가운데 에이전트 코드만 넘긴다. 어느 profile 의 목록인지는 Control Plane 이
 * 세션의 사용자와 그 에이전트로 정한다.
 */
export async function GET(request: Request) {
  const agentCode = new URL(request.url).searchParams.get("agentCode");
  if (!agentCode) {
    return errorResponse("VALIDATION_FAILED", "에이전트를 골라 주세요.", 400);
  }

  const result = await callControlPlane<unknown>(
    `/api/v1/chat/model-options?agentCode=${encodeURIComponent(agentCode)}`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
