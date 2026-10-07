import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 내 브라우저를 켠다. 본문이 없다. */
export async function POST() {
  const result = await callControlPlane("/api/v1/browser/start", {
    method: "POST",
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}
