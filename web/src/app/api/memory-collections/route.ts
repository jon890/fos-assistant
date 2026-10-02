import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";

export async function GET() {
  const result = await callControlPlane<unknown[]>(
    "/api/v1/memory-collections",
  );
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
