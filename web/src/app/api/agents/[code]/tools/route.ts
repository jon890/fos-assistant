import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";

function invalidCode(): NextResponse {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않습니다." },
    { status: 400 },
  );
}

/** Control Plane 본문을 그대로 보내 `missingToolsets` 같은 오류 칸을 보존한다. */
async function toolsResponse(path: string, body?: unknown): Promise<NextResponse> {
  const opened = await requestControlPlane(path, body === undefined ? {} : { method: "PUT", body });
  if (!opened.ok) {
    return NextResponse.json({ code: opened.code, message: opened.message }, { status: opened.status });
  }
  return new NextResponse(opened.response.body, {
    status: opened.response.status,
    headers: { "Content-Type": opened.response.headers.get("Content-Type") ?? "application/json" },
  });
}

export async function GET(_request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) return invalidCode();
  return toolsResponse(`/api/v1/agents/${code}/tools`);
}

export async function PUT(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) return invalidCode();
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 본문이 올바르지 않습니다." }, { status: 400 });
  }
  return toolsResponse(`/api/v1/agents/${code}/tools`, body);
}
