import { requestControlPlane } from "@/lib/control-plane";
import { readControlPlaneResult } from "@/lib/control-plane-result";
import { errorResponse } from "@/lib/api-response";

/**
 * 내 브라우저의 로그인 화면 SSE 를 그대로 흘린다. 꺼져 있으면 Control Plane 이 켠다.
 *
 * <p>시작 주소 `url` 만 넘긴다. 브라우저가 끊으면 `request.signal` 로 Control Plane 쪽 연결도 끊는다.
 */
export async function GET(request: Request) {
  const url = new URL(request.url).searchParams.get("url");
  const query = url ? `?${new URLSearchParams({ url })}` : "";
  let opened: Awaited<ReturnType<typeof requestControlPlane>>;
  try {
    opened = await requestControlPlane(`/api/v1/browser/screen${query}`, {
      signal: request.signal,
    });
  } catch {
    // Control Plane 에 닿지 못했다. 화면이 다시 열기를 판단하도록 502 JSON 으로 돌려준다.
    return errorResponse("INTERNAL_ERROR", "화면을 열지 못했어요.", 502);
  }
  if (!opened.ok) {
    return errorResponse(opened.code, opened.message, opened.status);
  }

  const upstream = opened.response;
  if (!upstream.ok || !upstream.body) {
    const result = readControlPlaneResult(
      upstream.status,
      await upstream.text(),
    );
    return result.ok
      ? errorResponse("INTERNAL_ERROR", "화면을 열지 못했어요.", 502)
      : errorResponse(result.code, result.message, result.status);
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
