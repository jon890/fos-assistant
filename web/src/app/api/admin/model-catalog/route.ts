import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 그룹 하나의 모델 목록을 읽는다. 단계별 모델을 고르는 칸이 쓴다. */
export async function GET() {
  const result = await callControlPlane<unknown>("/api/v1/admin/model-catalog");
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
