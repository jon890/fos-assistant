import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 받아들이거나 거절할 수 있는 대체 제안이다. */
export async function GET() {
  const result = await callControlPlane<unknown[]>(
    "/api/v1/memory-links/pending",
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
