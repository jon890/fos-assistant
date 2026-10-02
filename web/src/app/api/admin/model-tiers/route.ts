import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자가 고칠 그룹의 단계 정의와 그룹 기본 단계를 읽는다. 에이전트를 받지 않는다. */
export async function GET() {
  const result = await callControlPlane<unknown>("/api/v1/admin/model-tiers");
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
