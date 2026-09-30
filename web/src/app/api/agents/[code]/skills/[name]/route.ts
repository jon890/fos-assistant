import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { SKILL_NAME_PATTERN } from "@/lib/skill";

type RouteContext = { params: Promise<{ code: string; name: string }> };

/** 경로의 코드와 이름이 형식에 맞는지 본다. 어긋나면 그대로 돌려줄 응답을 준다. */
async function parsePath(context: RouteContext): Promise<{ code: string; name: string } | NextResponse> {
  const { code, name } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않아요." }, { status: 400 });
  }
  if (!SKILL_NAME_PATTERN.test(name)) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "스킬 이름 형식이 올바르지 않아요." }, { status: 400 });
  }
  return { code, name };
}

export async function GET(_request: Request, context: RouteContext) {
  const path = await parsePath(context);
  if (path instanceof NextResponse) return path;
  const result = await callControlPlane(`/api/v1/agents/${path.code}/skills/${path.name}`);
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}

export async function PUT(request: Request, context: RouteContext) {
  const path = await parsePath(context);
  if (path instanceof NextResponse) return path;
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." }, { status: 400 });
  }
  const result = await callControlPlane(`/api/v1/agents/${path.code}/skills/${path.name}`, {
    method: "PUT",
    body,
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}

export async function DELETE(_request: Request, context: RouteContext) {
  const path = await parsePath(context);
  if (path instanceof NextResponse) return path;
  const result = await callControlPlane(`/api/v1/agents/${path.code}/skills/${path.name}`, { method: "DELETE" });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return new NextResponse(null, { status: 204 });
}
