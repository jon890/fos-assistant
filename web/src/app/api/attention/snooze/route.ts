import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

/** 그 카드에서 항목을 정한 시각까지 미룬다. 기한은 브라우저가 계산해 보낸다. 성공하면 본문 없이 204 다. */
export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane("/api/v1/attention/snooze", {
    method: "POST",
    body: parsed.body,
  });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return new NextResponse(null, { status: 204 });
}
