import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

export async function GET() {
  const result = await callControlPlane<unknown[]>("/api/v1/agents");
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body;
  const result = await callControlPlane("/api/v1/agents", { method: "POST", body });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data, { status: 201 });
}
