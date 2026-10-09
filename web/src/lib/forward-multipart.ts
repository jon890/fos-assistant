import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/**
 * multipart 업로드를 Control Plane 의 같은 경로로 흘려보낸다. 본문을 읽어 다시 만들지 않는다.
 *
 * <p>413 은 `tooLargeMessage` 로, JSON 이 아닌 응답은 `VALIDATION_FAILED` 로 바꿔 화면이 언제나 같은
 * 오류 모양을 받게 한다.
 */
export async function forwardMultipart(
  path: string,
  request: Request,
  tooLargeMessage: string,
): Promise<NextResponse> {
  const opened = await forwardControlPlane(path, {
    method: "POST",
    body: request.body,
    contentType: request.headers.get("content-type"),
  });
  if (!opened.ok) {
    return errorResponse(opened.code, opened.message, opened.status);
  }

  const upstream = opened.response;
  const text = await upstream.text();
  // 역방향 프록시가 413 을 HTML 로 돌려주는 등 JSON 이 아닌 응답이 올 수 있다. 그때는 그대로 던지지
  // 않고 VALIDATION_FAILED 로 옮긴다.
  if (upstream.status === 413) {
    return errorResponse("VALIDATION_FAILED", tooLargeMessage, 400);
  }
  let payload: unknown = null;
  try {
    payload = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    return errorResponse("VALIDATION_FAILED", "요청을 처리하지 못했어요.", 400);
  }
  return NextResponse.json(payload, { status: upstream.status });
}
