import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

/** 발급 응답은 토큰 원문을 담는다. 어떤 캐시에도 남기지 않는다. */
const NO_STORE = { "Cache-Control": "no-store" };

export async function GET() {
  const result = await callControlPlane<unknown[]>("/api/v1/service-tokens");
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data, { headers: NO_STORE });
}

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane("/api/v1/service-tokens", {
    method: "POST",
    body: parsed.body,
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data, { headers: NO_STORE });
}
