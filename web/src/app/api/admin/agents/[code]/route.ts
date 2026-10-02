import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

const CODE = /^[a-z0-9][a-z0-9-]*$/;

export async function PATCH(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!CODE.test(code)) {
    return errorResponse("VALIDATION_FAILED", "에이전트 코드 형식이 올바르지 않아요.", 400);
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(`/api/v1/admin/agents/${code}`, {
    method: "PATCH",
    body: parsed.body,
  });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
