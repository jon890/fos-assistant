import { NextResponse } from "next/server";
import { connectorCall, connectionResponse } from "@/lib/connection-route";
import type { AdminAccountbookConnection } from "@/lib/connection";

export async function POST(_: Request, { params }: { params: Promise<{ userId: string }> }) {
  const { userId } = await params;
  if (!/^\d+$/.test(userId) || Number(userId) < 1) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "입력 내용을 다시 확인해 주세요." }, { status: 400 });
  }
  return connectionResponse(await connectorCall<AdminAccountbookConnection>(
    `/api/v1/admin/connections/accountbook/${userId}/confirm`, { method: "POST" },
  ));
}
