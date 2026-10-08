import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { readControlPlaneResult } from "@/lib/control-plane-result";
import { errorResponse } from "@/lib/api-response";

/** Control Plane 과 같은 본문 바이트 상한이다. */
const MAX_BODY = 8 * 1024;

/** 열린 로그인 화면에 입력 하나를 보낸다. 본문은 읽어 크기만 보고 그대로 넘긴다. 성공하면 본문 없이 204 다. */
export async function POST(request: Request) {
  const body = await request.text();
  if (new TextEncoder().encode(body).length > MAX_BODY) {
    return errorResponse("VALIDATION_FAILED", "요청 내용이 너무 커요.", 400);
  }
  const opened = await forwardControlPlane("/api/v1/browser/screen/input", {
    method: "POST",
    body: new Blob([body]).stream(),
    contentType: "application/json",
  });
  if (!opened.ok) {
    return errorResponse(opened.code, opened.message, opened.status);
  }
  const result = readControlPlaneResult(
    opened.response.status,
    await opened.response.text(),
  );
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return new NextResponse(null, { status: 204 });
}
