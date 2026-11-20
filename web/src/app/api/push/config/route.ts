import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 서버의 웹 푸시 설정이다. 키가 없으면 `enabled` 가 거짓이고 공개 키가 없다. */
export async function GET() {
  const result = await callControlPlane<unknown>("/api/v1/push/config");
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
