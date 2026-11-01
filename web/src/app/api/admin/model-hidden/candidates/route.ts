import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

/** 숨길 모델을 고르는 후보를 읽는다. 켜진 모든 에이전트 profile 목록의 합집합이다. */
export async function GET() {
  const result = await callControlPlane<unknown>(
    "/api/v1/admin/model-hidden/candidates",
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
