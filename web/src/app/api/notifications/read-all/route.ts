import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 내 알림을 모두 읽음으로 표시한다. */
export async function POST() {
  const result = await callControlPlane<unknown>(
    "/api/v1/notifications/read-all",
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
