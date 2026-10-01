import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 현재 에이전트에 적용되는 단계와 기본값을 읽는다. */
export async function GET(request: Request) {
  const agentCode = new URL(request.url).searchParams.get("agentCode");
  if (!agentCode) {
    return errorResponse("VALIDATION_FAILED", "에이전트를 골라 주세요.", 400);
  }

  const result = await callControlPlane<unknown>(
    `/api/v1/chat/model-tiers?agentCode=${encodeURIComponent(agentCode)}`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
