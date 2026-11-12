import { afterEach, expect, test } from "bun:test";
import { EXPRESSIONS, SEARCH_STATE } from "../src/expressions.ts";
import { ToolError } from "../src/errors.ts";
import { forgetLeftTabs, readInTab } from "../src/tab.ts";
import {
  startFakeBrowser,
  type FakeBrowser,
  type FakePage,
} from "./fake-browser.ts";

let browser: FakeBrowser | null = null;
afterEach(() => {
  browser?.stop();
  forgetLeftTabs();
});

const TARGET = "https://search.shopping.naver.com/ns/search?query=x";

async function read(page: FakePage) {
  browser = startFakeBrowser(page);
  return readInTab(browser.url, TARGET, SEARCH_STATE, same, {
    timeoutMs: 5_000,
    pollMs: 1,
  });
}

/** 꺼낸 글을 그대로 돌려주는 읽기 함수다. */
const same = (raw: unknown) => raw;

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

for (const [name, page, code, need] of [
  [
    "로그인",
    { href: "https://nid.naver.com/nidlogin.login" },
    "NAVER_SHOPPING_LOGIN_REQUIRED",
    "login",
  ],
  ["캡차", { captcha: true }, "NAVER_SHOPPING_CAPTCHA", "captcha"],
  [
    "기기 인증",
    { href: "https://nid.naver.com/login/ext/deviceConfirm" },
    "NAVER_SHOPPING_DEVICE_CHECK",
    "device_check",
  ],
] as const) {
  test(`${name} 화면이면 아무것도 누르지 않고 탭을 남긴 채 그 코드와 넘기기 칸으로 끝난다`, async () => {
    await expect(read({ ...page, state: "[]" })).rejects.toMatchObject({
      code,
      handoff: { need, tab: "tab-1" },
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
    readInTab(browser.url, TARGET, SEARCH_STATE, same, {
      timeoutMs: 200,
      pollMs: 5,
    }),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_UNAVAILABLE",
  });
  expect(browser.http).toContain("GET /json/close/tab-1");
});

test("이동이 망 오류로 실패하면 바뀐 화면이 아니라 일시 장애로 끝나고 그 탭을 닫는다", async () => {
  await expect(
    read({ navigateError: "net::ERR_NAME_NOT_RESOLVED", state: null }),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_UNAVAILABLE",
  });
  expect(browser!.http).toContain("GET /json/close/tab-1");
  expect(
    browser!.commands.some((command) => command.method === "Runtime.evaluate"),
  ).toBe(false);
});

test("다음 탭 열기는 새 탭을 열기 전에 앞서 사람에게 넘긴 탭을 닫는다", async () => {
  await expect(
    read({ href: "https://nid.naver.com/nidlogin.login", state: "[]" }),
  ).rejects.toMatchObject({ code: "NAVER_SHOPPING_LOGIN_REQUIRED" });
  browser!.stop();
  browser = startFakeBrowser({ state: "[]" });
  expect(
    await readInTab(browser.url, TARGET, SEARCH_STATE, same, {
      timeoutMs: 5_000,
      pollMs: 1,
    }),
  ).toBe("[]");
  expect(browser.http.indexOf("GET /json/close/tab-1")).toBeLessThan(
    browser.http.indexOf("PUT /json/new?about:blank"),
  );
});

test("이동 뒤 최종 주소가 네이버가 아니면 읽지 않고 탭을 남긴 채 예상 밖 화면 넘기기로 끝난다", async () => {
  await expect(
    read({ href: "https://example.com/landing", state: "[]" }),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_PAGE_CHANGED",
    handoff: { need: "unexpected_page", tab: "tab-1" },
  });
  expect(browser!.http.some((line) => line.includes("/json/close/"))).toBe(
    false,
  );
  expect(
    browser!.commands.some(
      (command) =>
        command.method === "Runtime.evaluate" &&
        command.params.expression === SEARCH_STATE,
    ),
  ).toBe(false);
  expectNoInput(browser!);
});

test("읽기 함수가 바뀐 화면을 내면 탭을 남긴 채 예상 밖 화면 넘기기로 끝나고, 다음 탭 열기가 그 탭을 닫는다", async () => {
  browser = startFakeBrowser({ state: "{}" });
  await expect(
    readInTab(
      browser.url,
      TARGET,
      SEARCH_STATE,
      () => {
        throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
      },
      { timeoutMs: 5_000, pollMs: 1 },
    ),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_PAGE_CHANGED",
    handoff: { need: "unexpected_page", tab: "tab-1" },
  });
  expect(browser.http.some((line) => line.includes("/json/close/"))).toBe(
    false,
  );
  browser.stop();
  expect(await read({ state: "[]" })).toBe("[]");
  expect(browser!.http.indexOf("GET /json/close/tab-1")).toBeLessThan(
    browser.http.indexOf("PUT /json/new?about:blank"),
  );
});

test("읽기 함수가 없는 상품을 내면 넘기지 않고 그 탭을 닫는다", async () => {
  browser = startFakeBrowser({ state: "{}" });
  const error = await readInTab(
    browser.url,
    TARGET,
    SEARCH_STATE,
    () => {
      throw new ToolError("NAVER_SHOPPING_NOT_FOUND");
    },
    { timeoutMs: 5_000, pollMs: 1 },
  ).catch((caught) => caught);
  expect(error).toBeInstanceOf(ToolError);
  expect(error.code).toBe("NAVER_SHOPPING_NOT_FOUND");
  expect(error.handoff).toBeNull();
  expect(browser.http).toContain("GET /json/close/tab-1");
});
