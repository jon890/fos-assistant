import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

/** 본문의 구독 주소와 같은 내 기기를 지운다. 없어도 204 다. */
export async function DELETE(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane("/api/v1/push/devices/current", {
    method: "DELETE",
    body: parsed.body,
  });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return new NextResponse(null, { status: 204 });
}
