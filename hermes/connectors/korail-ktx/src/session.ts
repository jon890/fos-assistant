import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { ToolError } from "./errors.ts";

export type Env = Record<string, string | undefined>;

/** 바인딩 설치와 확인 호출이 넣는 중계 주소 env 의 이름이다. assistant.json 의 `owner_browser_env` 와 같다. */
export const BROWSER_URL_ENV = "KORAIL_KTX_BROWSER_URL";

/**
 * 중계 주소의 모양이다. `<gateway-base-url>/<접근 표식>` 이다.
 * 대시보드의 `OWNER_BROWSER_VALUE_RE` 와 같은 식이다(ADR-20261008 browser-gateway-token).
 */
export const BROWSER_URL_PATTERN =
  /^https?:\/\/[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(\/[A-Za-z0-9._~-]{1,128}){1,8}$/;

/**
 * 코레일에는 로그인했을 때만 생기는 쿠키를 아직 찾지 못했다. 로그인 전에도 `JSESSIONID`, `WMONID` 가 있다
 * (설계 「공개 화면 확인 결과」). 그래서 쿠키로 로그인을 가리지 않고 `logged_in` 을 `unknown` 으로 답한다.
 * 로그인이 필요한 화면은 다음 도구가 로그인 화면을 만나 `KORAIL_LOGIN_REQUIRED` 로 드러난다.
 */
const KORAIL_DOMAINS = new Set(["www.korail.com", ".korail.com", "korail.com"]);

/** 중계 주소를 다시 검사한다. 비었으면 중계가 꺼진 것이라 브라우저에 닿지 못한다고 답한다. */
export function browserUrl(env: Env) {
  const cdpUrl = env[BROWSER_URL_ENV] ?? "";
  if (!cdpUrl) throw new ToolError("KORAIL_BROWSER_UNREACHABLE");
  if (!BROWSER_URL_PATTERN.test(cdpUrl)) throw new ToolError("KORAIL_INVALID_INPUT");
  return cdpUrl;
}

/**
 * 브라우저 대상에 붙어 코레일 쿠키가 있는지 본다. 탭은 열지 않는다.
 * 코레일 쿠키가 하나도 없으면 「내 브라우저」 에서 코레일을 연 적이 없는 것이라 로그인이 필요하다고 답한다.
 */
export async function checkLogin(env: Env, { timeoutMs = 8_000 }: { timeoutMs?: number } = {}) {
  const cdpUrl = browserUrl(env);
  const deadline = Date.now() + timeoutMs;
  const remaining = () => Math.max(1, deadline - Date.now());
  // 중계는 꺼진 브라우저를 켜느라 이 요청을 붙잡을 수 있어 남은 시간을 모두 쓴다.
  const version = await httpJson(cdpUrl, "/json/version", { timeoutMs: remaining() });
  const debuggerUrl = (version as { webSocketDebuggerUrl?: unknown } | null)?.webSocketDebuggerUrl;
  if (typeof debuggerUrl !== "string") throw new ToolError("KORAIL_BROWSER_UNREACHABLE");
  const session = await CdpSession.connect(wsUrlFor(cdpUrl, debuggerUrl), remaining());
  try {
    const { cookies } = await session.send<{ cookies?: unknown }>("Storage.getCookies", {}, remaining());
    const korail = (Array.isArray(cookies) ? cookies : []).some((cookie) => KORAIL_DOMAINS.has(cookie?.domain));
    if (!korail) throw new ToolError("KORAIL_LOGIN_REQUIRED");
    return { browser: "connected", logged_in: "unknown" };
  } finally {
    session.close();
  }
}
