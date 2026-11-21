import { callControlPlane } from "@/lib/control-plane";
import type { ControlPlaneResult } from "@/lib/control-plane-result";

/** 서비스 화면이 돌려보낼 이 웹의 주소 경로다. 관리자가 OAuth 앱에 등록하는 주소의 경로와 같다. */
export const OAUTH_CALLBACK_PATH = "/connections/oauth/callback";

/**
 * 돌아오는 주소를 요청이 온 공개 주소로 만든다. Control Plane 이 이 주소를 상태 값 서명에 넣어 바꿔치기를 막는다.
 * 같은 출처 검사를 지난 요청이라 `Origin` 이 있으면 그것을 쓰고, 없으면 앞단이 넘긴 host 와 scheme 을 쓴다.
 */
export function oauthCallbackUrl(request: Request): string {
  const origin = request.headers.get("origin");
  if (origin && origin !== "null")
    return new URL(OAUTH_CALLBACK_PATH, origin).href;
  const url = new URL(request.url);
  const host =
    request.headers.get("x-forwarded-host")?.split(",")[0]?.trim() ||
    request.headers.get("host") ||
    url.host;
  const proto =
    request.headers.get("x-forwarded-proto")?.split(",")[0]?.trim() ||
    url.protocol.replace(":", "");
  return new URL(OAUTH_CALLBACK_PATH, `${proto}://${host}`).href;
}

/** Control Plane 을 부르고 응답에서 계약의 칸만 옮긴다. 모양이 틀리면 연결 실패로 바꾼다. */
export async function oauthCall<T>(
  path: string,
  body: unknown,
  parse: (value: unknown) => T,
): Promise<ControlPlaneResult<T>> {
  try {
    const result = await callControlPlane<unknown>(path, {
      method: "POST",
      body,
    });
    return result.ok ? { ...result, data: parse(result.data) } : result;
  } catch {
    return {
      ok: false,
      status: 502,
      code: "CONNECTOR_OPERATION_FAILED",
      message: "",
    };
  }
}
