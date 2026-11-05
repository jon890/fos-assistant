import { NextResponse } from "next/server";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { CONNECTOR_ID_PATTERN } from "@/lib/connection";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

type RouteContext = { params: Promise<{ code: string; connectorId: string }> };

/** 이 에이전트에 붙인 내 연결 하나의 사건 감지를 켜거나 끈다. */
export async function PUT(request: Request, context: RouteContext) {
  const { code, connectorId } = await context.params;
  if (
    !AGENT_CODE_PATTERN.test(code) ||
    !CONNECTOR_ID_PATTERN.test(connectorId)
  ) {
    return errorResponse(
      "VALIDATION_FAILED",
      "에이전트나 플러그인 형식이 올바르지 않아요.",
      400,
    );
  }

  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;

  const result = await callControlPlane(
    `/api/v1/agents/${code}/event-watches/${connectorId}`,
    { method: "PUT", body: parsed.body },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
