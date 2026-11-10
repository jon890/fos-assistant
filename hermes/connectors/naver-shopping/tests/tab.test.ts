import { afterEach, expect, test } from "bun:test";
import { EXPRESSIONS, SEARCH_STATE } from "../src/expressions.ts";
import { readInTab } from "../src/tab.ts";
import {
  startFakeBrowser,
  type FakeBrowser,
  type FakePage,
} from "./fake-browser.ts";

let browser: FakeBrowser | null = null;
afterEach(() => browser?.stop());

const TARGET = "https://search.shopping.naver.com/ns/search?query=x";

async function read(page: FakePage) {
  browser = startFakeBrowser(page);
  return readInTab(browser.url, TARGET, SEARCH_STATE, {
    timeoutMs: 5_000,
    pollMs: 1,
  });
}

/** 누르기와 입력을 보내지 않았고, 화면에서 부른 식이 모두 정해 둔 상수인지 본다. */
function expectNoInput(fake: FakeBrowser) {
  expect(
    fake.commands.some((command) => command.method.startsWith("Input.")),
  ).toBe(false);
  for (const command of fake.commands.filter(
    (c) => c.method === "Runtime.evaluate",
  ))
    expect(EXPRESSIONS.has(command.params.expression)).toBe(true);
}

test("빈 탭을 열고 처리기를 건 뒤 이동하며, 정상으로 읽으면 그 탭을 닫는다", async () => {
  expect(await read({ state: "[]", loadingPolls: 2 })).toBe("[]");
  const methods = browser!.commands.map((command) => command.method);
  expect(methods.indexOf("Page.enable")).toBeLessThan(
    methods.indexOf("Page.navigate"),
  );
  expect(
    browser!.commands.find((c) => c.method === "Page.navigate")!.params,
  ).toEqual({ url: TARGET });
  expect(browser!.http).toContain("PUT /json/new?about:blank");
  expect(browser!.http).toContain("GET /json/close/tab-1");
  expectNoInput(browser!);
});

test("우리 탭에 뜬 대화상자는 닫기로 넘긴다", async () => {
  await read({ state: "[]", dialog: true });
  await Bun.sleep(20);
  expect(browser!.commands).toContainEqual({
    method: "Page.handleJavaScriptDialog",
    params: { accept: false },
  });
});

for (const [name, page, code] of [
  [
    "로그인",
    { href: "https://nid.naver.com/nidlogin.login" },
    "NAVER_SHOPPING_LOGIN_REQUIRED",
  ],
  ["캡차", { captcha: true }, "NAVER_SHOPPING_CAPTCHA"],
  [
    "기기 인증",
    { href: "https://nid.naver.com/login/ext/deviceConfirm" },
    "NAVER_SHOPPING_DEVICE_CHECK",
  ],
] as const) {
  test(`${name} 화면이면 아무것도 누르지 않고 탭을 남긴 채 그 코드로 끝난다`, async () => {
    await expect(read({ ...page, state: "[]" })).rejects.toMatchObject({
      code,
    });
    expect(browser!.http.some((line) => line.includes("/json/close/"))).toBe(
      false,
    );
    expectNoInput(browser!);
  });
}

test("제한 시간 안에 준비되지 않으면 실패하고 그 탭을 닫는다", async () => {
  browser = startFakeBrowser({ loadingPolls: 1_000_000 });
  await expect(
    readInTab(browser.url, TARGET, SEARCH_STATE, { timeoutMs: 200, pollMs: 5 }),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_UNAVAILABLE",
  });
  expect(browser.http).toContain("GET /json/close/tab-1");
});
