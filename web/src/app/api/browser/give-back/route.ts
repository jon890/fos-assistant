import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 「돌려주기」 다. 잠금만 푼다. 본문이 없고 성공하면 204 다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const result = await callControlPlane("/api/v1/browser/give-back", {
    method: "POST",
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return new NextResponse(null, { status: 204 });
}
