import { errorResponse } from "@/lib/api-response";

/**
 * 요청이 이 화면에서 왔는지 본다. 세션 쿠키의 SameSite 기본값에만 기대지 않으려고 둔다.
 *
 * <p>판정 규칙이다.
 * - `Origin` 이 있으면 그 host 가 요청 host 와 같아야 한다. 요청 host 는 `x-forwarded-host`, 없으면 `Host` 다.
 *   로그인 화면의 Server Action 이 이미 Next.js 의 같은 비교로 운영에서 돌고 있어 그 규칙을 따른다.
 * - `Origin` 이 없으면 Next.js 의 Server Action 처럼 막지 않는다. 브라우저는 POST 와 DELETE 에 늘
 *   `Origin` 을 싣고, 그것이 없는 호출자는 남의 세션 쿠키를 실어 보낼 수 없다.
 *   다만 브라우저가 `Sec-Fetch-Site` 로 다른 사이트에서 왔다고 알리면 막는다. 같은 출처의 GET 은
 *   `Origin` 을 싣지 않으므로 GET 라우트는 이 값으로 가린다.
 */
export function isSameOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (origin === null) {
    const site = request.headers.get("sec-fetch-site");
    return site !== "cross-site" && site !== "same-site";
  }
  const host =
    request.headers.get("x-forwarded-host")?.split(",")[0]?.trim() ||
    request.headers.get("host");
  if (!host) return false;
  try {
    return new URL(origin).host === host;
  } catch {
    // `null` 같은 불투명 출처나 틀린 값이다. 어디서 왔는지 모르므로 막는다.
    return false;
  }
}

/** 다른 출처의 요청이면 403 응답을, 아니면 `null` 을 돌려준다. Control Plane 을 부르기 전에 쓴다. */
export function rejectCrossOrigin(request: Request): Response | null {
  return isSameOrigin(request)
    ? null
    : errorResponse("FORBIDDEN", "이 화면에서만 할 수 있어요.", 403);
}
