import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 모든 사용자 브라우저의 상태다. 권한은 Control Plane 이 확인한다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/admin/browsers");
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
