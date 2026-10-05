import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 사이드바 「지금 볼 것」 의 건수를 읽는다. 사건을 남기지 않는다. */
export async function GET() {
  const result = await callControlPlane<{ nowCount: number }>(
    "/api/v1/attention/summary",
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
