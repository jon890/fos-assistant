import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

const CODE = /^[a-z0-9][a-z0-9-]*$/;

export async function POST(_request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!CODE.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않습니다." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/admin/agents/${code}/sync-model`, { method: "POST" });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
