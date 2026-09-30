import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}

export async function GET() {
  return response(await callControlPlane<unknown[]>("/api/v1/admin/people"));
}

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body;
  return response(await callControlPlane("/api/v1/admin/people", { method: "POST", body }));
}
