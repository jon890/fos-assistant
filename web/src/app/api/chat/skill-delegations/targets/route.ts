import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 비서 대화의 `/` 목록에 함께 보일, 맡길 수 있는 전문 에이전트와 그 켜진 스킬을 읽는다. */
export async function GET() {
  const result = await callControlPlane<unknown>(
    "/api/v1/chat/skill-delegations/targets",
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
