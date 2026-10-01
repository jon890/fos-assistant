import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

/** 그룹 관리자가 단계 정의와 그룹 기본값을 함께 저장한다. */
export async function PUT(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane<unknown>(
    "/api/v1/chat/model-tiers/group",
    {
      method: "PUT",
      body: parsed.body,
    },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
