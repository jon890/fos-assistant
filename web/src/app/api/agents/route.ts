import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

export async function GET() {
  const result = await callControlPlane<unknown[]>("/api/v1/agents");
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." }, { status: 400 });
  }
  const result = await callControlPlane("/api/v1/agents", { method: "POST", body });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data, { status: 201 });
}
