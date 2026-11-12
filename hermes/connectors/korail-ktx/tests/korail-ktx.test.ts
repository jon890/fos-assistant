import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, test } from "bun:test";
import manifest from "../assistant.json";
import { ToolError, toolFailure } from "../src/errors.ts";
import { createServer } from "../src/server.ts";
import { BROWSER_URL_ENV, checkLogin } from "../src/session.ts";
import { startFakeBrowser, type FakeBrowser } from "./fake-browser.ts";

let browser: FakeBrowser | null = null;
afterEach(() => browser?.stop());

async function connect(env: Record<string, string>) {
  const client = new Client({ name: "korail-ktx-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await createServer(env).connect(serverTransport);
  await client.connect(clientTransport);
  return client;
}

test("코레일 쿠키가 있으면 연결됐다고 답하고 로그인은 모른다고 하며 탭을 열지 않는다", async () => {
  browser = startFakeBrowser();
  expect(await checkLogin({ [BROWSER_URL_ENV]: browser.url })).toEqual({
    browser: "connected",
    logged_in: "unknown",
  });
  expect(browser.http.some((line) => line.includes("/json/new"))).toBe(false);
});

test("코레일 쿠키가 하나도 없으면 로그인이 필요하다고 실패한다", async () => {
  browser = startFakeBrowser();
  browser.cookies.splice(0, 1, { name: "NID_AUT", domain: ".naver.com" });
  await expect(
    checkLogin({ [BROWSER_URL_ENV]: browser.url }),
  ).rejects.toMatchObject({ code: "KORAIL_LOGIN_REQUIRED" });
});

test("중계 주소가 비면 닿지 않음, 모양이 틀리면 입력 오류다", async () => {
  await expect(checkLogin({})).rejects.toMatchObject({
    code: "KORAIL_BROWSER_UNREACHABLE",
  });
  await expect(
    checkLogin({ [BROWSER_URL_ENV]: "file:///etc/passwd" }),
  ).rejects.toMatchObject({ code: "KORAIL_INVALID_INPUT" });
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
    name: "check_korail_login",
    arguments: {},
  });
  const text = (result.content as { text: string }[])[0]!.text;
  expect(result.isError).toBe(true);
  expect(JSON.parse(text)).toEqual({
    error: { code: "KORAIL_BROWSER_UNREACHABLE" },
  });
  expect(text).not.toContain("gateway-token");
});

test("화면 넘기기는 isError 없는 결과에 status, 안내, 넘기기 칸을 싣고 그 밖의 실패는 isError 다", () => {
  const handoff = toolFailure(
    new ToolError("KORAIL_BOT_CHECK", null, { need: "bot_check", tab: "TAB1" }),
  );
  expect("isError" in handoff).toBe(false);
  const body = JSON.parse(handoff.content[0]!.text);
  expect(body.status).toBe("HUMAN_ACTION_REQUIRED");
  expect(body.message).toContain("다시 부르지 않는다");
  expect(body.handoff).toEqual({
    code: "KORAIL_BOT_CHECK",
    need: "bot_check",
    tab: "TAB1",
  });
  expect(toolFailure(new ToolError("KORAIL_REJECTED", "거절"))).toMatchObject({
    isError: true,
  });
});

test("선언은 입력 칸이 없고 모든 도구가 사람이 연 대화의 읽기이며 로그인 화면 주소가 코레일이다", () => {
  expect(manifest.fields).toEqual([]);
  expect(
    Object.values(manifest.tools).every(
      (tool) => tool.attended_only === true && tool.risk === "READ",
    ),
  ).toBe(true);
  expect(manifest.owner_browser_login_url).toBe(
    "https://www.korail.com/ticket/login",
  );
});
