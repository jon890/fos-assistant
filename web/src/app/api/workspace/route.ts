import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 파일 공간의 상태다. 루트가 설정되지 않아도 200 으로 `available: false` 를 받는다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/workspace");
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
