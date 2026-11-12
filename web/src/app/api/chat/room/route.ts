import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 요청자의 대화방 한 줄이다. 아직 열지 않았으면 `CONVERSATION_NOT_FOUND` 다. 사이드바가 맨 위 줄을 그릴 때 쓴다. */
export async function GET() {
  const result = await callControlPlane<unknown>(
    "/api/v1/chat/conversations/room",
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
