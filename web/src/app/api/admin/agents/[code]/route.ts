import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

const CODE = /^[a-z0-9][a-z0-9-]*$/;

export async function PATCH(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!CODE.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않아요." }, { status: 400 });
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(`/api/v1/admin/agents/${code}`, {
    method: "PATCH",
    body: parsed.body,
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
