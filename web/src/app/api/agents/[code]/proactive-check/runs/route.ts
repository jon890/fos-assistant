import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";

/** 살펴보기를 시작한다. 본문은 받지 않고, 시작하면 202 와 점검 대화의 공개 식별자를 넘긴다. */
export async function POST(
  _request: Request,
  context: { params: Promise<{ code: string }> },
) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "에이전트 코드 형식이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(
    `/api/v1/agents/${code}/proactive-check/runs`,
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data, { status: result.status });
}
