import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, test } from "bun:test";
import manifest from "../assistant.json";
import { createServer } from "../src/server.ts";
import { BROWSER_URL_ENV, checkLogin } from "../src/session.ts";
import { forgetLeftTabs } from "../src/tab.ts";
import { startFakeBrowser, type FakeBrowser } from "./fake-browser.ts";

let browser: FakeBrowser | null = null;
afterEach(() => browser?.stop());

async function connect(env: Record<string, string>) {
  const client = new Client({ name: "naver-shopping-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await createServer(env).connect(serverTransport);
  await client.connect(clientTransport);
  return client;
}

test("로그인 쿠키 둘이 있으면 연결됐다고 답하고 탭을 열지 않는다", async () => {
  browser = startFakeBrowser();
  expect(await checkLogin({ [BROWSER_URL_ENV]: browser.url })).toEqual({
    browser: "connected",
    logged_in: true,
  });
  expect(browser.http.some((line) => line.includes("/json/new"))).toBe(false);
});

test("로그인 쿠키가 없으면 로그인이 필요하다고 실패한다", async () => {
  browser = startFakeBrowser();
  browser.cookies.splice(0, 1);
  await expect(
    checkLogin({ [BROWSER_URL_ENV]: browser.url }),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_LOGIN_REQUIRED",
  });
});

test("중계 주소가 비면 닿지 않음, 모양이 틀리면 입력 오류다", async () => {
  await expect(checkLogin({})).rejects.toMatchObject({
    code: "NAVER_SHOPPING_BROWSER_UNREACHABLE",
  });
  await expect(
    checkLogin({ [BROWSER_URL_ENV]: "file:///etc/passwd" }),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_INVALID_INPUT",
  });
});

test("서버의 도구는 선언과 같고 모두 읽기 전용이며 설명은 선언 그대로다", async () => {
  const client = await connect({});
  const { tools } = await client.listTools();
  expect(tools.map((tool) => tool.name).sort()).toEqual(
    Object.keys(manifest.tools).sort(),
  );
  for (const tool of tools) {
    expect(tool.annotations?.readOnlyHint).toBe(true);
    expect(tool.description).toBe(
      manifest.tools[tool.name as keyof typeof manifest.tools].description,
    );
  }
});

test("도구 오류는 코드만 싣고 중계 주소를 싣지 않는다", async () => {
  const client = await connect({
    [BROWSER_URL_ENV]: "http://127.0.0.1:9/gateway-token-0123456789",
  });
  const result = await client.callTool({
    name: "check_shopping_login",
    arguments: {},
  });
  const text = (result.content as { text: string }[])[0]!.text;
  expect(result.isError).toBe(true);
  expect(JSON.parse(text)).toEqual({
    error: { code: "NAVER_SHOPPING_BROWSER_UNREACHABLE" },
  });
  expect(text).not.toContain("gateway-token");
});

test("사람이 해야 하는 화면이면 오류가 아닌 결과에 넘기기 칸(code, need, 남긴 탭)을 싣는다", async () => {
  browser = startFakeBrowser({
    href: "https://nid.naver.com/nidlogin.login",
    state: "[]",
  });
  const client = await connect({ [BROWSER_URL_ENV]: browser.url });
  const result = await client.callTool({
    name: "search_products",
    arguments: { query: "물티슈" },
  });
  expect(result.isError).toBeFalsy();
  const body = JSON.parse((result.content as { text: string }[])[0]!.text);
  expect(body).toMatchObject({
    status: "HUMAN_ACTION_REQUIRED",
    handoff: {
      code: "NAVER_SHOPPING_LOGIN_REQUIRED",
      need: "login",
      tab: "tab-1",
    },
  });
  // 모델이 성공으로 읽지 않도록, 끝나지 않았다는 말과 사람이 풀어야 한다는 안내가 글에 든다.
  expect(body.message).toContain("사람이 해야 하는 화면에서 멈췄");
  expect(body.message).toContain("끝나지 않았");
  expect(body).not.toHaveProperty("error");
  forgetLeftTabs();
});

test("넘기기 결과가 연달아 와도 Hermes 의 연속 실패 집계(isError)에 들어가지 않는다", async () => {
  browser = startFakeBrowser({ captcha: true, state: "[]" });
  const client = await connect({ [BROWSER_URL_ENV]: browser.url });
  // Hermes 는 같은 서버의 isError 결과가 연속 3회면 60초 동안 호출을 막는다. 그 횟수보다 많이 부른다.
  for (let call = 0; call < 5; call++) {
    const result = await client.callTool({
      name: "search_products",
      arguments: { query: "물티슈" },
    });
    expect(result.isError).toBeFalsy();
    const body = JSON.parse((result.content as { text: string }[])[0]!.text);
    expect(body.handoff).toMatchObject({
      code: "NAVER_SHOPPING_CAPTCHA",
      need: "captcha",
    });
  }
  forgetLeftTabs();
});

test("화면 넘기기가 아닌 실패는 그대로 isError 결과다", async () => {
  const client = await connect({});
  const result = await client.callTool({
    name: "search_products",
    arguments: { query: "물티슈" },
  });
  expect(result.isError).toBe(true);
  expect(JSON.parse((result.content as { text: string }[])[0]!.text)).toEqual({
    error: { code: "NAVER_SHOPPING_BROWSER_UNREACHABLE" },
  });
});

test("선언은 입력 칸이 없고 모든 도구가 사람이 연 대화에서만 돌며 로그인 화면 주소가 네이버다", () => {
  expect(manifest.fields).toEqual([]);
  expect(
    Object.values(manifest.tools).every(
      (tool) => tool.attended_only === true && tool.risk === "READ",
    ),
  ).toBe(true);
  expect(new URL(manifest.owner_browser_login_url).hostname).toBe(
    "nid.naver.com",
  );
});
