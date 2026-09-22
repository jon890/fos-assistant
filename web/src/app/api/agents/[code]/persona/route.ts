import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

/** 에이전트 코드 형식이다. 백엔드의 `AgentDtos` 와 `HermesProfileName` 이 두는 64자 상한을 같이 둔다. */
const CODE = /^[a-z0-9][a-z0-9-]{0,63}$/;

export async function GET(_request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!CODE.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않습니다." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/agents/${code}/persona`);
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}

export async function PUT(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!CODE.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않습니다." }, { status: 400 });
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 본문이 올바르지 않습니다." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/agents/${code}/persona`, {
    method: "PUT",
    body,
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
