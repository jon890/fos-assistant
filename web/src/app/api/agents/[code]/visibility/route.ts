import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";

export async function PATCH(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않아요." }, { status: 400 });
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/agents/${code}/visibility`, { method: "PATCH", body });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
