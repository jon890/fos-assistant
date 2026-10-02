import { NextResponse } from "next/server";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

type RouteContext = { params: Promise<{ code: string }> };

/** 관리자가 에이전트 기본 모델과 그룹의 숨김을 정할 때 보는 값을 읽는다. */
export async function GET(_request: Request, context: RouteContext) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "에이전트 코드 형식이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane<unknown>(
    `/api/v1/admin/agents/${code}/model-settings`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
