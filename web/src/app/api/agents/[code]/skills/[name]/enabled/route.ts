import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { SKILL_NAME_PATTERN } from "@/lib/skill";

export async function PUT(request: Request, context: { params: Promise<{ code: string; name: string }> }) {
  const { code, name } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않아요." }, { status: 400 });
  }
  if (!SKILL_NAME_PATTERN.test(name)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "스킬 이름 형식이 올바르지 않아요." }, { status: 400 });
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/agents/${code}/skills/${name}/enabled`, {
    method: "PUT",
    body,
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return new NextResponse(null, { status: 204 });
}
