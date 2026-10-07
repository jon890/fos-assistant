import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, test } from "bun:test";
import { wsUrlFor } from "../src/cdp.ts";
import { createServer } from "../src/server.ts";
import { sessionStatus, type Env } from "../src/session.ts";
import { closedPortUrl, FakeCdp, upstreamText } from "./fake-cdp.ts";

const BLOG_ID = "example-blog";
const fakes: FakeCdp[] = [];

afterEach(() => {
  while (fakes.length) fakes.pop()!.stop();
});

function fake(cookies?: Array<{ name: string; domain: string }>) {
  const cdp = new FakeCdp();
  if (cookies) cdp.on("Storage.getCookies", () => ({ cookies }));
  fakes.push(cdp);
  return cdp;
}

const env = (cdpUrl: string, blogId = BLOG_ID): Env => ({
  NAVER_BLOG_CDP_URL: cdpUrl,
  NAVER_BLOG_ID: blogId,
});

const LOGGED_IN = [
  { name: "NID_AUT", domain: ".naver.com" },
  { name: "NID_SES", domain: ".naver.com" },
  { name: "other", domain: ".example.com" },
];

/** MCP 서버의 session_status 를 부르고, 결과 글에 CDP 주소와 브라우저 원문이 없는지 함께 본다. */
async function callSessionStatus(values: Env) {
  const client = new Client({ name: "naver-blog-test", version: "1.0.0" });
  const server = createServer(values);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: "session_status", arguments: {} });
    const text = (result.content as Array<{ text: string }>)[0]?.text ?? "";
    const cdpUrl = values.NAVER_BLOG_CDP_URL ?? "";
    expect(text).not.toContain(cdpUrl);
    expect(text).not.toContain(new URL(cdpUrl).host);
    expect(text).not.toContain(upstreamText);
    return { isError: result.isError === true, body: JSON.parse(text) };
  } finally {
    await client.close();
    await server.close();
  }
}

test("로그인 쿠키 둘이 있으면 브라우저 대상의 쿠키만 읽고 logged_in 을 돌려준다", async () => {
  const cdp = fake(LOGGED_IN);

  const { isError, body } = await callSessionStatus(env(cdp.url));

  expect(isError).toBe(false);
  expect(body).toEqual({ browser: "connected", logged_in: true, blog_id: BLOG_ID });
  expect(cdp.calls).toEqual([
    { target: "browser/fake-browser", method: "Storage.getCookies", params: {} },
  ]);
  expect(cdp.httpRequests).not.toContain("PUT /json/new");
  expect(cdp.origins).toEqual(["http://localhost"]);
  expect(cdp.violations).toEqual([]);
});

test("끝에 / 가 붙은 연결 주소도 같은 창구로 붙는다", async () => {
  const cdp = fake(LOGGED_IN);

  const result = await sessionStatus(env(`${cdp.url}/`));

  expect(result.logged_in).toBe(true);
  expect(cdp.httpRequests[0]).toBe("GET /json/version");
});

test.each([
  ["NID_SES 가 없다", [{ name: "NID_AUT", domain: ".naver.com" }]],
  [
    "NID_SES 가 다른 도메인이다",
    [
      { name: "NID_AUT", domain: ".naver.com" },
      { name: "NID_SES", domain: ".example.com" },
    ],
  ],
  ["쿠키가 없다", []],
])("%s 면 NAVER_BLOG_LOGIN_REQUIRED 로 실패한다", async (_, cookies) => {
  const cdp = fake(cookies);

  const { isError, body } = await callSessionStatus(env(cdp.url));

  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_BLOG_LOGIN_REQUIRED" } });
});

test("닫힌 포트면 NAVER_BLOG_BROWSER_UNREACHABLE 로 실패한다", async () => {
  const { isError, body } = await callSessionStatus(env(closedPortUrl()));

  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_BLOG_BROWSER_UNREACHABLE" } });
});

test("HTTP 창구가 답하지 않으면 정한 시간 안에 NAVER_BLOG_BROWSER_UNREACHABLE 로 끝난다", async () => {
  const cdp = fake(LOGGED_IN);
  cdp.hangHttp = true;
  const started = performance.now();

  const failure = await sessionStatus(env(cdp.url), { timeoutMs: 300 }).catch(
    (error) => error,
  );

  expect(failure.code).toBe("NAVER_BLOG_BROWSER_UNREACHABLE");
  expect(performance.now() - started).toBeLessThan(3_000);
});

test("쿠키 명령에 답하지 않으면 정한 시간 안에 NAVER_BLOG_BROWSER_UNREACHABLE 로 끝난다", async () => {
  const cdp = fake();
  const started = performance.now();

  const failure = await sessionStatus(env(cdp.url), { timeoutMs: 300 }).catch(
    (error) => error,
  );

  expect(failure.code).toBe("NAVER_BLOG_BROWSER_UNREACHABLE");
  expect(cdp.methods()).toEqual(["Storage.getCookies"]);
  expect(performance.now() - started).toBeLessThan(3_000);
});

test("브라우저가 다른 주소를 적어도 연결 칸의 호스트와 포트로 붙는다", async () => {
  const cdp = fake(LOGGED_IN);
  cdp.debuggerUrl = "ws://127.0.0.1:9/devtools/browser/fake-browser";

  const result = await sessionStatus(env(cdp.url));

  expect(result.logged_in).toBe(true);
  expect(cdp.calls.map((call) => call.target)).toEqual(["browser/fake-browser"]);
});

test("wsUrlFor 는 경로만 남기고 http 는 ws, https 는 wss 로 바꾼다", () => {
  expect(wsUrlFor("http://192.0.2.10:1/", "ws://127.0.0.1:9/devtools/browser/abc")).toBe(
    "ws://192.0.2.10:1/devtools/browser/abc",
  );
  expect(wsUrlFor("https://localhost", "ws://127.0.0.1:9/devtools/page/xyz")).toBe(
    "wss://localhost/devtools/page/xyz",
  );
});

test.each([
  ["호스트 이름", env("http://chrome.example.internal:1")],
  ["경로가 붙은 주소", env("http://192.0.2.10:1/json")],
  ["다른 scheme", env("ftp://192.0.2.10:1")],
  ["모양이 틀린 블로그 아이디", env("http://192.0.2.10:1", "example blog")],
  ["빈 블로그 아이디", env("http://192.0.2.10:1", "")],
])("%s 은 연결하지 않고 NAVER_BLOG_INVALID_INPUT 으로 실패한다", async (_, values) => {
  const { isError, body } = await callSessionStatus(values);

  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_BLOG_INVALID_INPUT" } });
});

test("브라우저가 CDP 오류를 주면 원문 없이 NAVER_BLOG_UNAVAILABLE 로 실패한다", async () => {
  const cdp = fake();
  cdp.on("Storage.getCookies", () => {
    throw new Error("fail");
  });

  const { isError, body } = await callSessionStatus(env(cdp.url));

  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_BLOG_UNAVAILABLE" } });
});
