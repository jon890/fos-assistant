import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";

/** 기본 스킬은 서버의 관리자 경로에서만 읽는다. */
export async function GET(_request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse("VALIDATION_FAILED", "에이전트 코드 형식이 올바르지 않아요.", 400);
  }
  const result = await callControlPlane(`/api/v1/admin/agents/${code}/skills`);
  if (!result.ok) return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
