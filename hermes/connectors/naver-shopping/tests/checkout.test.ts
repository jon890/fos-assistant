import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, test } from "bun:test";
import {
  CHOOSE_OPTION,
  CLICKING,
  EXPRESSIONS,
  FILL_FUNCTIONS,
  OPEN_LAYER_OPTION,
  OPEN_OPTION_LISTS,
  ORDER_SHEET_AMOUNT,
  PAGE_PROBE,
  PRESS_BUY,
  PRESS_BUY_NOW,
  PRODUCT_STATE,
  READY_STATE,
  SET_QUANTITY,
} from "../src/expressions.ts";
import { createServer } from "../src/server.ts";
import { BROWSER_URL_ENV } from "../src/session.ts";
import { forgetLeftTabs, readInTab, workInTab } from "../src/tab.ts";
import {
  approvalEnv,
  PLAIN_PRODUCT,
  PRODUCT_URL,
  startFakeShop,
  type FakeShop,
  type ShopConfig,
} from "./fake-shop.ts";

let shop: FakeShop | null = null;
afterEach(() => {
  shop?.stop();
  shop = null;
  forgetLeftTabs();
});

const TAB = { pollMs: 1, optionWaitMs: 30 };
const FAST = { work: workInTab, tab: TAB, stepMs: 1, waitMs: 150 };

async function call(
  config: ShopConfig,
  name: string,
  args: Record<string, unknown>,
  approval?: Record<string, string>,
) {
  shop = startFakeShop(config);
  const env: Record<string, string | undefined> = {
    [BROWSER_URL_ENV]: shop.url,
    ...(approval ?? {}),
  };
  const client = new Client({ name: "checkout-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await createServer(env, {
    read: readInTab,
    tab: TAB,
    checkout: { work: workInTab, tab: TAB },
    fill: FAST,
  }).connect(serverTransport);
  await client.connect(clientTransport);
  const result = await client.callTool({ name, arguments: args });
  const text = (result.content as { text: string }[])[0]!.text;
  let body: any = text;
  try {
    body = JSON.parse(text);
  } catch {
    // 서버 스키마가 막은 인자는 SDK 의 평문 오류다.
  }
  return { isError: result.isError === true, body, env };
}

const prepare = (config: ShopConfig, args: Record<string, unknown>) =>
  call(config, "prepare_checkout", { v: 1, tool: "fill_checkout", args });

/** 승인한 실행처럼 원문과 ticket env 를 넣고 채우기를 부른다. 원문은 `args` 그대로다. */
async function fill(
  config: ShopConfig,
  args: Record<string, unknown>,
  approvalArgs: unknown = args,
) {
  shop = startFakeShop(config);
  const env: Record<string, string | undefined> = {
    [BROWSER_URL_ENV]: shop.url,
    ...approvalEnv(approvalArgs, shop.claimUrl),
  };
  const client = new Client({ name: "checkout-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await createServer(env, {
    read: readInTab,
    fill: FAST,
  }).connect(serverTransport);
  await client.connect(clientTransport);
  const result = await client.callTool({
    name: "fill_checkout",
    arguments: args,
  });
  return {
    isError: result.isError === true,
    body: JSON.parse((result.content as { text: string }[])[0]!.text),
    env,
    client,
  };
}

const PLAIN_ARGS = {
  checkout: 1,
  url: PRODUCT_URL,
  optionLabels: [],
  quantity: 1,
  expectedTotalKrw: 3400,
};

/** 옵션 둘(색, 크기)이 있는 상품. 처음 상태에는 옵션이 없고 드롭다운을 열면 실린다. */
const OPTION_PRODUCT = {
  ...PLAIN_PRODUCT,
  optionUsable: true,
  salePrice: 10000,
  productDeliveryInfo: {
    deliveryFeeType: "PAID",
    baseFee: 3000,
    freeConditionalAmount: 30000,
    area2ExtraFee: 5000,
  },
};
const OPENED_PRODUCT = {
  ...OPTION_PRODUCT,
  options: [{ groupName: "색" }, { groupName: "크기" }],
  optionCombinations: [
    { optionName1: "검정", optionName2: "L", price: 1000, stockQuantity: 5 },
    { optionName1: "검정", optionName2: "M", price: 0, stockQuantity: 0 },
    { optionName1: "흰색", optionName2: "L", price: 0, stockQuantity: 3 },
  ],
};

test("옵션 없는 상품의 준비는 카드 요약과 실행 원문을 만들고 누르지 않으며 탭을 닫는다", async () => {
  const { isError, body } = await prepare(
    { product: PLAIN_PRODUCT },
    { url: `${PRODUCT_URL}?NaPm=x`, options: [], quantity: 1 },
  );
  expect(isError).toBe(false);
  expect(body).toEqual({
    v: 1,
    executionArgs: {
      checkout: 1,
      url: PRODUCT_URL,
      optionLabels: [],
      quantity: 1,
      expectedTotalKrw: 3400,
    },
    summary: {
      kind: "CHECKOUT",
      title: "합성 상품",
      store: "합성몰",
      options: [],
      quantity: 1,
      itemAmountKrw: 500,
      shippingFeeKrw: 2900,
      expectedTotalKrw: 3400,
      regionalExtraFee: false,
      url: PRODUCT_URL,
    },
  });
  for (const source of CLICKING) expect(shop!.count(source)).toBe(0);
  expect(shop!.log).toContainEqual({
    kind: "http",
    line: "GET /json/close/TAB1",
  });
});

test("옵션 있는 상품의 준비는 드롭다운을 한 번만 열고 조합 하나로 맞춰 무료 조건과 지역 추가 배송비를 반영한다", async () => {
  const { isError, body } = await prepare(
    { product: OPTION_PRODUCT, productAfterOpen: OPENED_PRODUCT },
    { url: PRODUCT_URL, options: ["검정", "L"], quantity: 3 },
  );
  expect(isError).toBe(false);
  expect(body.summary).toMatchObject({
    options: [
      { name: "색", value: "검정" },
      { name: "크기", value: "L" },
    ],
    itemAmountKrw: 33000,
    shippingFeeKrw: 0,
    expectedTotalKrw: 33000,
    regionalExtraFee: true,
  });
  expect(body.executionArgs.optionLabels).toEqual(["검정", "L"]);
  // 읽기 도구의 누르기는 옵션 드롭다운 열기 하나뿐이고 한 번만 불린다.
  const clicks = shop!
    .cdpLines()
    .filter((line) => CLICKING.has(line.source ?? ""));
  expect(clicks.map((line) => line.source)).toEqual([OPEN_OPTION_LISTS]);
});

for (const [name, options, code] of [
  ["맞는 조합이 없음", ["빨강", "L"], "NAVER_SHOPPING_OPTION_MISMATCH"],
  ["단계 수가 다름", ["검정"], "NAVER_SHOPPING_OPTION_MISMATCH"],
  ["품절 조합", ["검정", "M"], "NAVER_SHOPPING_SOLD_OUT"],
] as const) {
  test(`준비는 ${name}이면 카드 없이 ${code} 로 거절한다`, async () => {
    const { isError, body } = await prepare(
      { product: OPTION_PRODUCT, productAfterOpen: OPENED_PRODUCT },
      { url: PRODUCT_URL, options, quantity: 1 },
    );
    expect(isError).toBe(true);
    expect(body).toEqual({ error: { code } });
  });
}

test("준비는 드롭다운을 열어도 옵션을 읽지 못하면 OPTIONS_UNKNOWN, 판매 중이 아니면 NOT_BUYABLE 이다", async () => {
  let result = await prepare(
    { product: OPTION_PRODUCT },
    { url: PRODUCT_URL, options: ["검정"], quantity: 1 },
  );
  expect(result.body).toEqual({
    error: { code: "NAVER_SHOPPING_OPTIONS_UNKNOWN" },
  });
  expect(shop!.count(OPEN_OPTION_LISTS)).toBe(1);
  shop!.stop();
  result = await prepare(
    { product: { ...PLAIN_PRODUCT, productStatusType: "OUTOFSTOCK" } },
    { url: PRODUCT_URL, options: [], quantity: 1 },
  );
  expect(result.body).toEqual({
    error: { code: "NAVER_SHOPPING_NOT_BUYABLE" },
  });
});

test("준비는 스토어가 아닌 주소와 범위를 넘는 수량을 탭 없이 거절한다", async () => {
  for (const args of [
    {
      url: "https://search.shopping.naver.com/catalog/1",
      options: [],
      quantity: 1,
    },
    { url: PRODUCT_URL, options: [], quantity: 100 },
  ]) {
    const { isError, body } = await prepare({ product: PLAIN_PRODUCT }, args);
    expect(isError).toBe(true);
    expect(["NAVER_SHOPPING_INVALID_INPUT", undefined]).toContain(
      body.error?.code,
    );
    expect(
      shop!.log.some(
        (line) => line.kind === "http" && line.line.includes("/json/new"),
      ),
    ).toBe(false);
    shop!.stop();
  }
});

test("준비가 로그인 화면을 만나면 넘기기 칸 없는 오류로 끝내고 그 탭을 남긴다", async () => {
  const { isError, body } = await prepare(
    { product: PLAIN_PRODUCT, landing: "login" },
    { url: PRODUCT_URL, options: [], quantity: 1 },
  );
  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_LOGIN_REQUIRED" } });
  expect(
    shop!.log.some(
      (line) => line.kind === "http" && line.line.includes("/json/close/"),
    ),
  ).toBe(false);
});

test("상품 정보 읽기의 누르기는 옵션이 있는데 비었을 때만 드롭다운 열기 한 번이다", async () => {
  let result = await call({ product: PLAIN_PRODUCT }, "get_product_detail", {
    url: PRODUCT_URL,
  });
  expect(result.isError).toBe(false);
  for (const source of CLICKING) expect(shop!.count(source)).toBe(0);
  shop!.stop();
  result = await call(
    { product: OPTION_PRODUCT, productAfterOpen: OPENED_PRODUCT },
    "get_product_detail",
    { url: PRODUCT_URL },
  );
  expect(
    result.body.options.map((group: { name: string }) => group.name),
  ).toEqual(["색", "크기"]);
  expect(result.body.options_unknown).toBeUndefined();
  const clicks = shop!
    .cdpLines()
    .filter((line) => CLICKING.has(line.source ?? ""));
  expect(clicks.map((line) => line.source)).toEqual([OPEN_OPTION_LISTS]);
});

/** 채우기 기록의 주요 단계를 차례대로 낸다. */
function steps(fake: FakeShop) {
  const names = new Map<string, string>([
    [PRODUCT_STATE, "price"],
    [PRESS_BUY, "buy"],
    [OPEN_LAYER_OPTION, "open_option"],
    [CHOOSE_OPTION, "choose"],
    [SET_QUANTITY, "quantity"],
    [PRESS_BUY_NOW, "buy_now"],
    [ORDER_SHEET_AMOUNT, "amount"],
  ]);
  const out: string[] = [];
  for (const line of fake.log) {
    const step =
      line.kind === "claim"
        ? "claim"
        : line.kind === "cdp"
          ? names.get(line.source ?? "")
          : undefined;
    if (step && out.at(-1) !== step) out.push(step);
  }
  return out;
}

/** 누르기·입력 명령이 없고 식과 함수 선언이 모두 상수이며, 주문서 화면에서는 준비 상태·화면 분류·금액 읽기만 불렀다. */
function expectSafe(fake: FakeShop) {
  for (const line of fake.cdpLines()) {
    expect(line.method.startsWith("Input.")).toBe(false);
    if (line.method === "Runtime.evaluate")
      expect(EXPRESSIONS.has(line.source!)).toBe(true);
    if (line.method === "Runtime.callFunctionOn")
      expect(FILL_FUNCTIONS.has(line.source!)).toBe(true);
    if (line.screen === "sheet" && line.source !== undefined)
      expect([READY_STATE, PAGE_PROBE, ORDER_SHEET_AMOUNT]).toContain(
        line.source,
      );
  }
}

test("채우기는 가격 확인 → claim → 구매하기 → 바로구매 → 주문서 금액 순서로 가고 주문서 탭을 남긴다", async () => {
  const { isError, body } = await fill({ product: PLAIN_PRODUCT }, PLAIN_ARGS);
  expect(isError).toBe(false);
  expect(body).toEqual({
    status: "ORDER_SHEET_READY",
    finalAmountKrw: 3300,
    tab: "TAB1",
  });
  expect(steps(shop!)).toEqual([
    "price",
    "claim",
    "buy",
    "quantity",
    "buy_now",
    "amount",
  ]);
  expect(shop!.log.filter((line) => line.kind === "claim")).toHaveLength(1);
  expect(
    (shop!.log.find((line) => line.kind === "claim") as { body: any }).body,
  ).toMatchObject({
    tool: "fill_checkout",
    scope: {},
  });
  expect(shop!.tabs.has("TAB1")).toBe(true);
  expectSafe(shop!);
});

test("채우기는 층에서 옵션을 단계마다 고르고 수량을 맞춘 뒤 바로구매하고, 새 탭의 주문서를 읽고 상품 탭은 닫는다", async () => {
  const args = {
    ...PLAIN_ARGS,
    optionLabels: ["검정", "L"],
    quantity: 2,
    expectedTotalKrw: 25000,
  };
  const { body } = await fill(
    {
      product: OPTION_PRODUCT,
      productAfterOpen: OPENED_PRODUCT,
      layerOptions: 2,
      layerValues: ["검정", "L"],
      buyNow: "new",
      amounts: { total: 25000, summary: 25000 },
    },
    args,
  );
  expect(body).toEqual({
    status: "ORDER_SHEET_READY",
    finalAmountKrw: 25000,
    tab: "TAB2",
  });
  expect(steps(shop!)).toEqual([
    "price",
    "claim",
    "buy",
    "open_option",
    "choose",
    "open_option",
    "choose",
    "quantity",
    "buy_now",
    "amount",
  ]);
  const chosen = shop!
    .cdpLines()
    .filter((line) => line.source === CHOOSE_OPTION)
    .map((line) => line.args);
  expect(chosen).toEqual([["검정"], ["L"]]);
  expect(shop!.tabs.has("TAB1")).toBe(false);
  expect(shop!.tabs.has("TAB2")).toBe(true);
  // 사용자가 쓰던 탭은 읽지도 닫지도 않는다.
  expect(shop!.tabs.has("USER0")).toBe(true);
  expect(shop!.cdpLines().some((line) => line.tab === "USER0")).toBe(false);
  expectSafe(shop!);
});

test("claim 이 거절되면 구매하기를 누르지 않고 CLAIM_REJECTED 로 끝내며 탭을 닫는다", async () => {
  const { isError, body } = await fill(
    { product: PLAIN_PRODUCT, claimStatus: 409 },
    PLAIN_ARGS,
  );
  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_CLAIM_REJECTED" } });
  for (const source of [PRESS_BUY, PRESS_BUY_NOW])
    expect(shop!.count(source)).toBe(0);
  expect(shop!.tabs.has("TAB1")).toBe(false);
});

test("다시 계산한 예상 합계가 승인보다 크면 claim 없이 PRICE_CHANGED 다", async () => {
  const { body } = await fill(
    { product: { ...PLAIN_PRODUCT, salePrice: 600 } },
    PLAIN_ARGS,
  );
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_PRICE_CHANGED" } });
  expect(shop!.log.some((line) => line.kind === "claim")).toBe(false);
  expect(shop!.count(PRESS_BUY)).toBe(0);
});

test("claim 전에 로그인 화면이면 claim 없이 탭을 남긴 넘기기 결과다", async () => {
  const { isError, body } = await fill(
    { product: PLAIN_PRODUCT, landing: "login" },
    PLAIN_ARGS,
  );
  expect(isError).toBe(false);
  expect(body).toMatchObject({
    status: "HUMAN_ACTION_REQUIRED",
    handoff: {
      code: "NAVER_SHOPPING_LOGIN_REQUIRED",
      need: "login",
      tab: "TAB1",
    },
  });
  expect(shop!.log.some((line) => line.kind === "claim")).toBe(false);
});

test("층에서 옵션을 고르지 못하면 바로구매를 누르지 않고 CHECKOUT_FAILED 로 끝내며 탭을 닫는다", async () => {
  const { isError, body } = await fill(
    {
      product: OPTION_PRODUCT,
      productAfterOpen: OPENED_PRODUCT,
      layerOptions: 2,
      layerValues: ["검정"],
    },
    {
      ...PLAIN_ARGS,
      optionLabels: ["검정", "L"],
      quantity: 1,
      expectedTotalKrw: 14000,
    },
  );
  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_CHECKOUT_FAILED" } });
  expect(shop!.count(PRESS_BUY_NOW)).toBe(0);
  expect(shop!.tabs.has("TAB1")).toBe(false);
});

for (const [name, config] of [
  ["바로구매 뒤 주문서가 열리지 않음", { buyNow: "nothing" }],
  ["주문서의 두 금액이 다름", { amounts: { total: 3300, summary: 3400 } }],
  ["주문서 금액을 읽지 못함", { amounts: { total: null, summary: null } }],
  ["구매하기 뒤 층도 주문서도 없음", { buy: "nothing" }],
] as const) {
  test(`${name}이면 결과를 모르는 것(UNKNOWN)으로 탭을 남기고 다시 누르지 않는다`, async () => {
    const { isError, body } = await fill(
      { product: PLAIN_PRODUCT, ...config },
      PLAIN_ARGS,
    );
    expect(isError).toBe(false);
    expect(body).toEqual({ status: "UNKNOWN", tab: "TAB1" });
    expect(shop!.count(PRESS_BUY)).toBe(1);
    expect(shop!.count(PRESS_BUY_NOW)).toBeLessThanOrEqual(1);
    expect(shop!.log.filter((line) => line.kind === "claim")).toHaveLength(1);
    expect(shop!.tabs.has("TAB1")).toBe(true);
    expectSafe(shop!);
  });
}

test("구매하기가 주문서로 바로 가도 주문서 금액을 읽는다", async () => {
  const { body } = await fill(
    { product: PLAIN_PRODUCT, buy: "sheet-same" },
    PLAIN_ARGS,
  );
  expect(body).toEqual({
    status: "ORDER_SHEET_READY",
    finalAmountKrw: 3300,
    tab: "TAB1",
  });
  expect(shop!.count(PRESS_BUY_NOW)).toBe(0);
  expectSafe(shop!);
});

test("최종 금액이 승인한 예상 합계보다 크면 금액을 알리고 주문서 탭을 닫는다", async () => {
  const { body } = await fill(
    { product: PLAIN_PRODUCT, amounts: { total: 3401, summary: 3401 } },
    PLAIN_ARGS,
  );
  expect(body).toEqual({
    status: "ORDER_SHEET_READY",
    finalAmountKrw: 3401,
    tab: "TAB1",
  });
  expect(shop!.tabs.has("TAB1")).toBe(false);
});

test("실행 원문이 인자와 다르면 탭을 열지 않고 거절하고, 권한은 한 번만 쓴다", async () => {
  const { isError, body, client } = await fill(
    { product: PLAIN_PRODUCT },
    PLAIN_ARGS,
    { ...PLAIN_ARGS, expectedTotalKrw: 999999 },
  );
  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_CLAIM_REJECTED" } });
  expect(
    shop!.log.some(
      (line) => line.kind === "http" && line.line.includes("/json/new"),
    ),
  ).toBe(false);
  const again = await client.callTool({
    name: "fill_checkout",
    arguments: PLAIN_ARGS,
  });
  expect(JSON.parse((again.content as { text: string }[])[0]!.text)).toEqual({
    error: { code: "NAVER_SHOPPING_CLAIM_REJECTED" },
  });
});

test("서버를 만들면 권한 env 를 지운다", async () => {
  const { env } = await fill({ product: PLAIN_PRODUCT }, PLAIN_ARGS);
  expect(
    Object.keys(env).filter((key) => key.startsWith("FOS_APPROVAL_")),
  ).toEqual([]);
});

test("누르기 전부터 바로구매 단추가 보이면 층을 알아볼 수 없어 아무것도 누르지 않고 CHECKOUT_FAILED 다", async () => {
  const { isError, body } = await fill(
    { product: PLAIN_PRODUCT, buyNowOnProduct: true },
    PLAIN_ARGS,
  );
  expect(isError).toBe(true);
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_CHECKOUT_FAILED" } });
  for (const source of CLICKING) expect(shop!.count(source)).toBe(0);
});

test("층에서 실패했어도 그사이 새 탭에 주문서가 열렸으면 UNKNOWN 으로 그 탭을 알린다", async () => {
  const { isError, body } = await fill(
    {
      product: OPTION_PRODUCT,
      productAfterOpen: OPENED_PRODUCT,
      sheetOnLayerOption: true,
      layerOptions: 0,
    },
    {
      ...PLAIN_ARGS,
      optionLabels: ["검정", "L"],
      quantity: 1,
      expectedTotalKrw: 14000,
    },
  );
  expect(isError).toBe(false);
  expect(body).toEqual({ status: "UNKNOWN", tab: "TAB2" });
  expect(shop!.count(PRESS_BUY_NOW)).toBe(0);
  expect(shop!.tabs.has("TAB2")).toBe(true);
});

test("바로구매 뒤 금액 읽기가 CDP 오류로 끝나도 오류가 아니라 UNKNOWN 이다", async () => {
  const { isError, body } = await fill(
    { product: PLAIN_PRODUCT, amountError: true },
    PLAIN_ARGS,
  );
  expect(isError).toBe(false);
  expect(body).toEqual({ status: "UNKNOWN", tab: "TAB1" });
  expect(shop!.count(PRESS_BUY_NOW)).toBe(1);
  expect(shop!.tabs.has("TAB1")).toBe(true);
});

test("새 탭 주문서의 최종 금액이 승인보다 크면 그 주문서 탭과 상품 탭을 모두 닫는다", async () => {
  const { body } = await fill(
    {
      product: PLAIN_PRODUCT,
      buyNow: "new",
      amounts: { total: 3401, summary: 3401 },
    },
    PLAIN_ARGS,
  );
  expect(body).toEqual({
    status: "ORDER_SHEET_READY",
    finalAmountKrw: 3401,
    tab: "TAB2",
  });
  expect(shop!.tabs.has("TAB1")).toBe(false);
  expect(shop!.tabs.has("TAB2")).toBe(false);
  expect(shop!.tabs.has("USER0")).toBe(true);
});

test("층 없이 주문서가 바로 열렸는데 옵션이나 수량 2 이상을 승인했으면 금액을 읽지 않고 UNKNOWN 이다", async () => {
  const { isError, body } = await fill(
    { product: PLAIN_PRODUCT, buy: "sheet-same" },
    { ...PLAIN_ARGS, quantity: 2, expectedTotalKrw: 3900 },
  );
  expect(isError).toBe(false);
  expect(body).toEqual({ status: "UNKNOWN", tab: "TAB1" });
  expect(shop!.count(ORDER_SHEET_AMOUNT)).toBe(0);
  expect(shop!.tabs.has("TAB1")).toBe(true);
});
