import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 내 브라우저를 끈다. 본문이 없다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const result = await callControlPlane("/api/v1/browser/stop", {
    method: "POST",
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}
