import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 그룹 전체의 이번 달 실행 수와 예상 추가 사용 요금이다. 권한은 Control Plane 이 확인한다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/admin/overview/monthly");
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
