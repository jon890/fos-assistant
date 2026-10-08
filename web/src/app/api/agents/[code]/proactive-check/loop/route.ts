import { NextResponse } from "next/server";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

type RouteContext = { params: Promise<{ code: string }> };

function codeOf(code: string): NextResponse | null {
  if (AGENT_CODE_PATTERN.test(code)) return null;
  return errorResponse(
    "VALIDATION_FAILED",
    "에이전트 코드 형식이 올바르지 않아요.",
    400,
  );
}

export async function GET(_request: Request, context: RouteContext) {
  const { code } = await context.params;
  const invalid = codeOf(code);
  if (invalid) return invalid;

  const result = await callControlPlane(
    `/api/v1/agents/${code}/proactive-check/loop`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}

export async function PUT(request: Request, context: RouteContext) {
  const { code } = await context.params;
  const invalid = codeOf(code);
  if (invalid) return invalid;

  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;

  const result = await callControlPlane(
    `/api/v1/agents/${code}/proactive-check/loop`,
    { method: "PUT", body: parsed.body },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
