import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { HERMES_SKILL_NAME_PATTERN } from "@/lib/skill";
import { errorResponse } from "@/lib/api-response";

/** 일반 경로는 올린 스킬만, 관리자 경로는 기본 스킬까지 서버가 판정한다. */
export async function skillEnabledRoute(request: Request,
  context: { params: Promise<{ code: string; name: string }> }, admin: boolean) {
  const { code, name } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code) || !HERMES_SKILL_NAME_PATTERN.test(name)) {
    return errorResponse("VALIDATION_FAILED", "스킬 경로 형식이 올바르지 않아요.", 400);
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(`/api/v1/${admin ? "admin/" : ""}agents/${code}/skills/${name}/enabled`,
    { method: "PUT", body: parsed.body });
  if (!result.ok) return errorResponse(result.code, result.message, result.status);
  return new NextResponse(null, { status: 204 });
}
