import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok) return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}

export async function GET() {
  return response(await callControlPlane<unknown[]>("/api/v1/memories"));
}

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return response(await callControlPlane("/api/v1/memories", { method: "POST", body: parsed.body }));
}
