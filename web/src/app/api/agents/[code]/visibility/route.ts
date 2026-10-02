import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";

export async function PATCH(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse("VALIDATION_FAILED", "에이전트 코드 형식이 올바르지 않아요.", 400);
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body;
  const result = await callControlPlane(`/api/v1/agents/${code}/visibility`, { method: "PATCH", body });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
