import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 카탈로그가 선언한 OAuth 앱과 그 설정 상태다. client secret 원문은 없다. 권한은 Control Plane 이 확인한다. */
export async function GET() {
  const result = await callControlPlane(
    "/api/v1/admin/secrets/connector-oauth/apps",
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
