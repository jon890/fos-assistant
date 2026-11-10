import { afterEach, expect, test } from "bun:test";
import { EXPRESSIONS } from "../src/expressions.ts";
import {
  KTX_TAB,
  LOOKUP_BUTTON,
  NOTICE_CLOSE,
  SUSEO_TOGETHER,
} from "../src/fields.ts";
import { checkSearch, type SearchArgs } from "../src/input.ts";
import { BROWSER_URL_ENV } from "../src/session.ts";
import { searchInTab } from "../src/tab.ts";
import { searchTrains } from "../src/tools.ts";
import {
  startFakeBrowser,
  type FakeBrowser,
  type FakePage,
} from "./fake-browser.ts";

let browser: FakeBrowser | null = null;
afterEach(() => browser?.stop());

const NOW = new Date("2026-11-10T00:00:00Z");
const ARGS: SearchArgs = {
  departure: "서울",
  arrival: "부산",
  date: "2026-11-11",
  time_from: "07:00",
  adults: 1,
  children: 0,
  seniors: 0,
  seat_class: "general",
};
const LIST = JSON.stringify({
  empty: false,
  rows: [
    {
      type: "KTX",
      no: "015",
      departs: "07:40",
      arrives: "10:20",
      general: { text: "예매 59,800원", bookable: true },
      first: null,
    },
  ],
});
const BOT =
  "안내 메시지 매크로, 개발자도구 등 미허가 도구 사용 시 이용이 제한될 수 있습니다. CODE : -8003";
const OPTIONS = { timeoutMs: 3_000, stepMs: 200, pollMs: 1 };

function run(page: FakePage, args: Partial<SearchArgs> = {}) {
  browser = startFakeBrowser({ list: LIST, ...page });
  return searchInTab(
    browser.url,
    checkSearch({ ...ARGS, ...args }, NOW),
    OPTIONS,
  );
}

const searched = (fake: FakeBrowser) =>
  fake.commands
    .filter((c) => c.method === "DOM.performSearch")
    .map((c) => c.params.query);
const closed = (fake: FakeBrowser) =>
  fake.http.some((line) => line.includes("/json/close/"));

/** 탭에서 부른 식이 모두 정해 둔 상수인지 본다. */
function expectConstantExpressions(fake: FakeBrowser) {
  for (const command of fake.commands.filter(
    (c) => c.method === "Runtime.evaluate",
  ))
    expect(EXPRESSIONS.has(command.params.expression)).toBe(true);
}

test("빈 탭에서 조건을 넣고 조회를 한 번 누른 뒤 목록 고르기를 하고 꺼내며 탭을 닫는다", async () => {
  expect(await run({})).toBe(LIST);
  const fake = browser!;
  const methods = fake.commands.map((c) => c.method);
  expect(methods.indexOf("Page.enable")).toBeLessThan(
    methods.indexOf("Page.navigate"),
  );
  expect(fake.http).toContain("PUT /json/new?about:blank");
  expect(closed(fake)).toBe(true);
  const queries = searched(fake);
  expect(queries.filter((q) => q === LOOKUP_BUTTON)).toHaveLength(1);
  expect(queries).toContain(SUSEO_TOGETHER);
  expect(queries).toContain(KTX_TAB);
  expect(queries[0]).toBe(NOTICE_CLOSE);
  expect(
    fake.commands
      .filter((c) => c.method === "Input.insertText")
      .map((c) => c.params.text),
  ).toEqual(["서울", "부산"]);
  expect(methods).not.toContain("Runtime.enable");
  expectConstantExpressions(fake);
});

test("어린이·경로 인원만큼 더하기를 누르고 어른 0명이면 빼기를 누른다", async () => {
  await run({}, { adults: 0, children: 2, seniors: 1 });
  const queries = searched(browser!);
  expect(
    queries.filter((q) => q.includes('"adult"') && q.includes("minus")),
  ).toHaveLength(1);
  expect(
    queries.filter((q) => q.includes('"child"') && q.includes("plus")),
  ).toHaveLength(2);
  expect(
    queries.filter((q) => q.includes('"senior"') && q.includes("plus")),
  ).toHaveLength(1);
});

test("수서 함께 보기가 이미 골라져 있으면 누르지 않는다", async () => {
  await run({ suseoChecked: true });
  expect(searched(browser!)).not.toContain(SUSEO_TOGETHER);
});

for (const [name, page, code] of [
  [
    "로그인",
    { afterLookup: "https://www.korail.com/ticket/login" },
    "KORAIL_LOGIN_REQUIRED",
  ],
  [
    "매크로 탐지",
    { notice: BOT, noticeAfterLookupOnly: true },
    "KORAIL_BOT_CHECK",
  ],
  ["보안 문자", { captcha: true }, "KORAIL_CAPTCHA"],
] as const) {
  test(`${name} 화면이면 그 뒤로 누르지 않고 탭을 남긴 채 그 코드로 끝난다`, async () => {
    await expect(run(page)).rejects.toMatchObject({ code });
    const fake = browser!;
    expect(closed(fake)).toBe(false);
    const lastInput = fake.commands
      .map((c) => c.method)
      .lastIndexOf("Input.dispatchMouseEvent");
    const lastProbe = fake.commands
      .map((c) => c.method)
      .lastIndexOf("Runtime.evaluate");
    expect(lastInput).toBeLessThan(lastProbe);
  });
}

test("기다리던 자리 대신 로그인 화면으로 가면 시간 초과보다 먼저 로그인으로 끝난다", async () => {
  browser = startFakeBrowser({
    list: LIST,
    afterLookup: "https://www.korail.com/ticket/login",
    missing: [KTX_TAB],
  });
  await expect(
    searchInTab(browser.url, checkSearch(ARGS, NOW), {
      timeoutMs: 60_000,
      stepMs: 30_000,
      pollMs: 1,
    }),
  ).rejects.toMatchObject({ code: "KORAIL_LOGIN_REQUIRED" });
});

test("코레일이 대화상자로 거절하면 닫기로 넘기고 그 글로 끝나며 탭을 닫는다", async () => {
  await expect(
    run({ dialogOnLookup: "예매 가능 기간이 아닙니다." }),
  ).rejects.toMatchObject({
    code: "KORAIL_REJECTED",
    detail: "예매 가능 기간이 아닙니다.",
  });
  expect(browser!.commands).toContainEqual({
    method: "Page.handleJavaScriptDialog",
    params: { accept: false },
  });
  expect(closed(browser!)).toBe(true);
});

test("필수 자리를 찾지 못하면 화면이 바뀐 것으로 끝나고 탭을 닫으며 다시 하지 않는다", async () => {
  await expect(run({ missing: [LOOKUP_BUTTON] })).rejects.toMatchObject({
    code: "KORAIL_PAGE_CHANGED",
  });
  expect(closed(browser!)).toBe(true);
  expect(
    browser!.commands.filter((c) => c.method === "Page.navigate"),
  ).toHaveLength(1);
});

test("목록을 꺼내지 못하면 화면이 바뀐 것으로 끝난다", async () => {
  await expect(run({ list: null })).rejects.toMatchObject({
    code: "KORAIL_PAGE_CHANGED",
  });
});

test("이동이 망 오류면 일시 장애로 끝나고 탭을 닫는다", async () => {
  await expect(
    run({ navigateError: "net::ERR_NAME_NOT_RESOLVED" }),
  ).rejects.toMatchObject({ code: "KORAIL_UNAVAILABLE" });
  expect(closed(browser!)).toBe(true);
});

test("잘못된 인자면 탭을 열지 않는다", async () => {
  browser = startFakeBrowser({ list: LIST });
  await expect(
    searchTrains(
      { [BROWSER_URL_ENV]: browser.url },
      { ...ARGS, arrival: "부산진" },
      { search: searchInTab, now: () => NOW },
    ),
  ).rejects.toMatchObject({ code: "KORAIL_UNKNOWN_STATION" });
  expect(browser.http).toHaveLength(0);
});

test("도구는 조회 결과를 열차 목록으로 돌려준다", async () => {
  browser = startFakeBrowser({ list: LIST });
  const result = await searchTrains({ [BROWSER_URL_ENV]: browser.url }, ARGS, {
    search: searchInTab,
    tab: OPTIONS,
    now: () => NOW,
  });
  expect(result).toMatchObject({
    count: 1,
    trains: [
      { train_no: "015", seat_status: "available", adult_fare_krw: 59800 },
    ],
  });
});
