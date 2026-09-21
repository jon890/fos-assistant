import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

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
  const body = await request.json();
  return response(await callControlPlane("/api/v1/admin/people", { method: "POST", body }));
}
