import { NextResponse } from "next/server";

/** API 라우트가 돌려주는 오류 모양을 한곳에서 만든다. */
export function errorResponse(
  code: string,
  message: string,
  status: number,
): NextResponse {
  return NextResponse.json({ code, message }, { status });
}
