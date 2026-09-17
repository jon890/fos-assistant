import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

export async function GET() {
  const result = await callControlPlane<unknown[]>("/api/v1/chat/conversations");
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
