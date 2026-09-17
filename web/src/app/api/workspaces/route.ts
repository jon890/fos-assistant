import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

export type WorkspaceView = { code: string; name: string; visibility: string };

export async function GET() {
  const result = await callControlPlane<WorkspaceView[]>("/api/v1/workspaces");
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
