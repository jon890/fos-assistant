import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 요청자가 고칠 수 있는 항목의 확인 전 정비 후보다. */
export async function GET() {
  const result = await callControlPlane<unknown[]>(
    "/api/v1/memory/maintenance-candidates",
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
