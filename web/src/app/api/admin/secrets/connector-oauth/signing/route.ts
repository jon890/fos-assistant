import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 관리자. 단추 연결 서명 비밀의 상태다. 원문과 통로 주소는 없다. */
export async function GET() {
  const result = await callControlPlane(
    "/api/v1/admin/secrets/connector-oauth/signing",
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
