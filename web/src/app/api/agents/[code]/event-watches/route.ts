import { NextResponse } from "next/server";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

type RouteContext = { params: Promise<{ code: string }> };

/** 이 에이전트에 붙인 내 연결마다 사건 감지 상태를 읽는다. */
export async function GET(_request: Request, context: RouteContext) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "에이전트 코드 형식이 올바르지 않아요.",
      400,
    );
  }

  const result = await callControlPlane(`/api/v1/agents/${code}/event-watches`);
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
