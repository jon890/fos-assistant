import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 실행 공간마다 용량이다. 파일 이름은 응답에 없다. 권한은 Control Plane 이 확인한다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/admin/workspaces");
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
