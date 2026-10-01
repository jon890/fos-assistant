import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { HERMES_SKILL_NAME_PATTERN } from "@/lib/skill";

export async function PUT(request: Request, context: { params: Promise<{ code: string; name: string }> }) {
  const { code, name } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않아요." }, { status: 400 });
  }
  // Hermes 기본 스킬도 켜고 끄므로 올린 스킬 규칙이 아니라 Hermes 이름 규칙으로 본다.
  if (!HERMES_SKILL_NAME_PATTERN.test(name)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "스킬 이름 형식이 올바르지 않아요." }, { status: 400 });
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body;
  const result = await callControlPlane(`/api/v1/agents/${code}/skills/${name}/enabled`, {
    method: "PUT",
    body,
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return new NextResponse(null, { status: 204 });
}
