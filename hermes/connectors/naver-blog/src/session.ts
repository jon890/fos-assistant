import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { ToolError } from "./errors.ts";

export type Env = Record<string, string | undefined>;

/** connector.json 의 연결 칸과 같은 정규식이다. 호스트를 IPv4 와 localhost 로 한정한다. */
export const CDP_URL_PATTERN =
  /^https?:\/\/([0-9]{1,3}(\.[0-9]{1,3}){3}|localhost)(:[0-9]{1,5})?\/?$/;
export const BLOG_ID_PATTERN = /^[A-Za-z0-9_-]{1,50}$/;

const LOGIN_COOKIES = ["NID_AUT", "NID_SES"];
const NAVER_DOMAINS = new Set(["naver.com", ".naver.com"]);

/** 연결 값을 다시 검사한다. 등록 화면을 거치지 않은 값도 같은 모양만 받는다. */
export function readConnection(env: Env) {
  const cdpUrl = env.NAVER_BLOG_CDP_URL ?? "";
  const blogId = env.NAVER_BLOG_ID ?? "";
  if (!CDP_URL_PATTERN.test(cdpUrl) || !BLOG_ID_PATTERN.test(blogId))
    throw new ToolError("NAVER_BLOG_INVALID_INPUT");
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

  const version = await httpJson(cdpUrl, "/json/version", {
    timeoutMs: Math.min(remaining(), 5_000),
  });
  const debuggerUrl = (version as { webSocketDebuggerUrl?: unknown } | null)
    ?.webSocketDebuggerUrl;
  if (typeof debuggerUrl !== "string")
    throw new ToolError("NAVER_BLOG_BROWSER_UNREACHABLE");

  const session = await CdpSession.connect(
    wsUrlFor(cdpUrl, debuggerUrl),
    cdpUrl,
    remaining(),
  );
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
