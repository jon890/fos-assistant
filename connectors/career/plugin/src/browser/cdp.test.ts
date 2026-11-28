import { afterEach, expect, test } from "bun:test";
import { CareerError } from "../errors.ts";
import { browserEndpoint, CdpSession, httpJson, relayBrowser, wsUrlFor } from "./cdp.ts";

type Relay = { url: string; origins: (string | null)[]; stop: () => void };
const servers: Relay[] = [];

afterEach(() => {
  for (const server of servers.splice(0)) server.stop();
});

/** fos-assistant 중계의 대역이다. HTTP 로 `/token/json/*`, WebSocket 으로 `/token/devtools/page/<id>` 를 받는다. */
function relay(options: { status?: number; closeWith?: number; evaluate?: (expr: string) => unknown } = {}): Relay {
  const origins: (string | null)[] = [];
  const server = Bun.serve({
    port: 0,
    fetch(request, srv) {
      origins.push(request.headers.get("origin"));
      const url = new URL(request.url);
      if (options.status) return new Response("", { status: options.status });
      if (url.pathname.startsWith("/token/devtools/page/")) {
        if (srv.upgrade(request)) return undefined;
        return new Response("", { status: 400 });
      }
      if (url.pathname === "/token/json/new")
        return Response.json({ id: "TAB1", webSocketDebuggerUrl: `ws://elsewhere:1/token/devtools/page/TAB1` });
      if (url.pathname.startsWith("/token/json/close/")) return new Response("Target is closing");
      return new Response("", { status: 404 });
    },
    websocket: {
      open(ws) {
        if (options.closeWith) setTimeout(() => ws.close(options.closeWith, "held"), 20);
      },
      message(ws, raw) {
        if (options.closeWith) return;
        const message = JSON.parse(String(raw));
        const value = options.evaluate?.(message.params?.expression) ?? "OK";
        ws.send(JSON.stringify({ id: message.id, result: { result: { value } } }));
      },
    },
  });
  const entry = { url: `http://127.0.0.1:${server.port}/token`, origins, stop: () => server.stop(true) };
  servers.push(entry);
  return entry;
}

async function codeOf(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof CareerError) return error.code;
    throw error;
  }
  return "no error";
}

test("중계로 탭을 열고 페이지 식을 돌린다", async () => {
  const fake = relay({ evaluate: (expr) => `got:${expr}` });
  const { tabId, session } = await relayBrowser(fake.url).openTab("about:blank");
  expect(tabId).toBe("TAB1");
  expect(await session.evaluate<string>("1+1")).toEqual({ ok: true, value: "got:1+1" });
  session.close();
  await relayBrowser(fake.url).closeTab(tabId);
  // 중계는 HTTP 와 WebSocket 연결 요청 모두에서 Origin 헤더를 받지 않는다.
  expect(fake.origins.length).toBeGreaterThan(2);
  expect(fake.origins.every((origin) => origin === null)).toBe(true);
});

test("중계 주소가 비면 쓰지 않는다", () => {
  expect(browserEndpoint({})).toBeUndefined();
  expect(browserEndpoint({ CAREER_BROWSER_URL: "  " })).toBeUndefined();
  expect(browserEndpoint({ CAREER_BROWSER_URL: "${CAREER_BROWSER_URL}" })).toBeUndefined();
  expect(browserEndpoint({ CAREER_BROWSER_URL: "http://relay/token/" })).toBe("http://relay/token");
});

test("사람이 쥔 브라우저(423)와 닿지 않는 중계를 구분한다", async () => {
  expect(await codeOf(httpJson(relay({ status: 423 }).url, "/json/version"))).toBe("CAREER_BROWSER_IN_USE");
  expect(await codeOf(httpJson(relay({ status: 500 }).url, "/json/version"))).toBe("CAREER_BROWSER_UNREACHABLE");
  expect(await codeOf(httpJson("http://127.0.0.1:1/token", "/json/version"))).toBe("CAREER_BROWSER_UNREACHABLE");
  expect(await codeOf(relayBrowser(relay({ status: 423 }).url).openTab("about:blank"))).toBe("CAREER_BROWSER_IN_USE");
});

test("연결이 4423 으로 닫히면 대기 중 명령이 사람 잠금과 탭으로 끝난다", async () => {
  const fake = relay({ closeWith: 4423 });
  const { session } = await relayBrowser(fake.url).openTab("about:blank");
  let caught: unknown;
  try {
    await session.send("Runtime.evaluate", { expression: "1" }, 2_000);
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(CareerError);
  expect((caught as CareerError).code).toBe("CAREER_BROWSER_IN_USE");
  expect((caught as CareerError).details).toEqual({ tab: "TAB1" });
  // 이후 명령도 멈추지 않고 같은 방식으로 실패한다.
  expect(await codeOf(session.send("Runtime.evaluate"))).toBe("CAREER_BROWSER_IN_USE");
});

test("표식 밖의 WebSocket 경로에는 붙지 않는다", () => {
  expect(wsUrlFor("http://relay:8/token", "ws://x/token/devtools/page/A")).toBe("ws://relay:8/token/devtools/page/A");
  expect(wsUrlFor("https://relay/token", "ws://x/token/devtools/page/A")).toBe("wss://relay/token/devtools/page/A");
  expect(() => wsUrlFor("http://relay/token", "ws://x/other/devtools/page/A")).toThrow(CareerError);
  expect(() => wsUrlFor("http://relay/token", "ws://x/token/json/version")).toThrow(CareerError);
  expect(() => wsUrlFor("http://relay/token", "not a url")).toThrow(CareerError);
});

test("닫힌 연결에 명령을 보내면 닿지 않음으로 끝난다", async () => {
  const fake = relay();
  const { session } = await relayBrowser(fake.url).openTab("about:blank");
  session.close();
  expect(await codeOf(session.send("Runtime.evaluate"))).toBe("CAREER_BROWSER_UNREACHABLE");
  expect(CdpSession).toBeDefined();
});
