import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 「직접 할게요」 다. 본문은 `{ conversationId? }` 이고 성공하면 본문 없이 204 다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane("/api/v1/browser/takeover", {
    method: "POST",
    body: parsed.body,
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return new NextResponse(null, { status: 204 });
}
