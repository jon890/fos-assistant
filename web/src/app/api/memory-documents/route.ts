import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data, {
    headers: { "Cache-Control": "no-store" },
  });
}

/** 목록은 본문을 싣지 않는다. 본문은 문서 하나를 열 때만 받는다. */
export async function GET() {
  return response(
    await callControlPlane<unknown[]>("/api/v1/memory-documents"),
  );
}

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return response(
    await callControlPlane("/api/v1/memory-documents", {
      method: "POST",
      body: parsed.body,
    }),
  );
}
