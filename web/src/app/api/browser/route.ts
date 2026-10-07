import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

function respond(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}

/** 내 브라우저의 상태다. 기능이 꺼져 있어도 200 으로 `enabled: false` 를 받는다. */
export async function GET() {
  return respond(await callControlPlane("/api/v1/browser"));
}

/** 내 브라우저를 만든다. 꺼진 채로 생긴다. 본문이 없다. */
export async function POST() {
  return respond(await callControlPlane("/api/v1/browser", { method: "POST" }));
}

/** 내 브라우저를 끄고 프로필까지 지운다. */
export async function DELETE() {
  return respond(
    await callControlPlane("/api/v1/browser", { method: "DELETE" }),
  );
}
