import { NextResponse } from "next/server";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

type RouteContext = { params: Promise<{ code: string }> };

function invalidCode() {
  return errorResponse(
    "VALIDATION_FAILED",
    "에이전트 코드 형식이 올바르지 않아요.",
    400,
  );
}

/** 관리자가 에이전트가 받는 기억 영역과 영역별 항목 수, 최근 변경을 읽는다. */
export async function GET(_request: Request, context: RouteContext) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) return invalidCode();
  const result = await callControlPlane<unknown>(
    `/api/v1/admin/agents/${code}/memory-collections`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}

/** 관리자가 에이전트가 받을 기억 영역 전체를 저장한다. 빈 목록이면 모두 뗀다. */
export async function PUT(request: Request, context: RouteContext) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) return invalidCode();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane<unknown>(
    `/api/v1/admin/agents/${code}/memory-collections`,
    { method: "PUT", body: parsed.body },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
