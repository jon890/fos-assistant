import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 웹 푸시 VAPID 키를 지워 푸시를 끄고 모든 사용자의 등록 기기를 비운다. */
export async function DELETE(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const result = await callControlPlane("/api/v1/admin/secrets/vapid", {
    method: "DELETE",
  });
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
