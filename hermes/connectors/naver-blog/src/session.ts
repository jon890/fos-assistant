import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { ToolError } from "./errors.ts";

export type Env = Record<string, string | undefined>;

/**
 * 바인딩 설치와 확인 호출이 넣는 중계 주소의 모양이다. `<gateway-base-url>/<접근 표식>` 이다.
 * 대시보드의 `OWNER_BROWSER_VALUE_RE` 와 같은 식이다(ADR-20261008 browser-gateway-token).
 */
export const BROWSER_URL_PATTERN =
  /^https?:\/\/[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(\/[A-Za-z0-9._~-]{1,128}){1,8}$/;
export const BLOG_ID_PATTERN = /^[A-Za-z0-9_-]{1,50}$/;

const LOGIN_COOKIES = ["NID_AUT", "NID_SES"];
const NAVER_DOMAINS = new Set(["naver.com", ".naver.com"]);

/**
 * 연결 값을 다시 검사한다. 등록 화면을 거치지 않은 값도 같은 모양만 받는다.
 * 중계 주소가 비었으면 중계가 꺼진 것이라 브라우저에 닿지 못한다고 답한다.
 */
export function readConnection(env: Env) {
  const cdpUrl = env.NAVER_BLOG_BROWSER_URL ?? "";
  const blogId = env.NAVER_BLOG_ID ?? "";
  if (!BLOG_ID_PATTERN.test(blogId)) throw new ToolError("NAVER_BLOG_INVALID_INPUT");
  if (!cdpUrl) throw new ToolError("NAVER_BLOG_BROWSER_UNREACHABLE");
  if (!BROWSER_URL_PATTERN.test(cdpUrl)) throw new ToolError("NAVER_BLOG_INVALID_INPUT");
  return { cdpUrl, blogId };
}

/**
 * 브라우저 대상에 붙어 `.naver.com` 의 로그인 쿠키 둘이 있는지 본다. 탭은 열지 않는다.
 * 글쓰기 주소를 열어 리다이렉트를 기다리면 확인 도구의 10초 제한을 넘길 수 있어서다.
 */
export async function sessionStatus(
  env: Env,
  { timeoutMs = 8_000 }: { timeoutMs?: number } = {},
) {
  const { cdpUrl, blogId } = readConnection(env);
  const deadline = Date.now() + timeoutMs;
  const remaining = () => Math.max(1, deadline - Date.now());

  // 중계는 꺼진 브라우저를 켜느라 이 요청을 붙잡을 수 있어 남은 시간을 모두 쓴다.
  const version = await httpJson(cdpUrl, "/json/version", { timeoutMs: remaining() });
  const debuggerUrl = (version as { webSocketDebuggerUrl?: unknown } | null)
    ?.webSocketDebuggerUrl;
  if (typeof debuggerUrl !== "string")
    throw new ToolError("NAVER_BLOG_BROWSER_UNREACHABLE");

  const session = await CdpSession.connect(wsUrlFor(cdpUrl, debuggerUrl), remaining());
  try {
    const { cookies } = await session.send<{ cookies?: unknown }>(
      "Storage.getCookies",
      {},
      remaining(),
    );
    const names = new Set(
      (Array.isArray(cookies) ? cookies : [])
        .filter((cookie) => NAVER_DOMAINS.has(cookie?.domain))
        .map((cookie) => cookie?.name),
    );
    if (!LOGIN_COOKIES.every((name) => names.has(name)))
      throw new ToolError("NAVER_BLOG_LOGIN_REQUIRED");
    return { browser: "connected", logged_in: true, blog_id: blogId };
  } finally {
    session.close();
  }
}
