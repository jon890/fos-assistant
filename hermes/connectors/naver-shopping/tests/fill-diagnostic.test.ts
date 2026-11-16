/**
 * 주문서 채우기의 진단 줄(design.md 「채우기 진단」). 단계마다 실패를 만들어 그 단계 이름과 오류 이름이 나오는지,
 * 줄에 주소, ticket, 화면 글이 섞이지 않는지 본다.
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, beforeEach, expect, test } from "bun:test";
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
let written: string[] = [];
const originalWrite = process.stderr.write;

beforeEach(() => {
  written = [];
  process.stderr.write = ((chunk: unknown) => {
    written.push(String(chunk));
    return true;
  }) as typeof process.stderr.write;
});

afterEach(() => {
  process.stderr.write = originalWrite;
  shop?.stop();
  shop = null;
  forgetLeftTabs();
});

const TAB = { pollMs: 1, optionWaitMs: 30 };

const PLAIN_ARGS = {
  checkout: 1,
  url: PRODUCT_URL,
  optionLabels: [] as string[],
  quantity: 1,
  expectedTotalKrw: 3400,
};

/** 옵션 둘이 있는 상품. 처음 상태에는 옵션이 없고 드롭다운을 열면 실린다. */
const OPTION_PRODUCT = {
  ...PLAIN_PRODUCT,
  optionUsable: true,
  salePrice: 10000,
  productDeliveryInfo: { deliveryFeeType: "PAID", baseFee: 3000 },
};
const OPENED_PRODUCT = {
  ...OPTION_PRODUCT,
  options: [{ groupName: "색" }, { groupName: "크기" }],
  optionCombinations: [
    { optionName1: "검정", optionName2: "L", price: 1000, stockQuantity: 5 },
  ],
};
const OPTION_ARGS = {
  ...PLAIN_ARGS,
  optionLabels: ["검정", "L"],
  expectedTotalKrw: 14000,
};

/** 승인한 실행처럼 채우기를 부르고 결과와 env 의 ticket 을 돌려준다. */
async function fill(
  config: ShopConfig,
  args: Record<string, unknown> = PLAIN_ARGS,
  approvalArgs: unknown = args,
  work: typeof workInTab = workInTab,
) {
  shop = startFakeShop(config);
  const approval = approvalEnv(approvalArgs, shop.claimUrl);
  const env: Record<string, string | undefined> = {
    [BROWSER_URL_ENV]: shop.url,
    ...approval,
  };
  const client = new Client({ name: "fill-diagnostic", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await createServer(env, {
    read: readInTab,
    fill: { work, tab: TAB, stepMs: 1, waitMs: 150 },
  }).connect(serverTransport);
  await client.connect(clientTransport);
  const result = await client.callTool({
    name: "fill_checkout",
    arguments: args,
  });
  return {
    body: JSON.parse((result.content as { text: string }[])[0]!.text),
    ticket: approval.FOS_APPROVAL_TICKET,
  };
}

/** 채우기가 남긴 진단 줄의 `[stage, code]`. code 가 없으면 단계만 싣는다. */
function stages() {
  return written
    .flatMap((chunk) => chunk.split("\n"))
    .filter(Boolean)
    .map((line) => JSON.parse(line).naver_shopping_diagnostic)
    .filter((entry) => entry?.tool === "fill_checkout")
    .map((entry) =>
      "code" in entry ? [entry.stage, entry.code] : [entry.stage],
    );
}

/** 마지막 줄. 끝난 단계와 오류 이름이다. */
const last = () => stages().at(-1);

test("끝까지 가면 단계마다 한 줄을 남기고 done 으로 끝난다", async () => {
  const { body } = await fill(
    {
      product: OPTION_PRODUCT,
      productAfterOpen: OPENED_PRODUCT,
      layerOptions: 2,
      layerValues: ["검정", "L"],
      amounts: { total: 14000, summary: 14000 },
    },
    OPTION_ARGS,
  );
  expect(body.status).toBe("ORDER_SHEET_READY");
  expect(stages()).toEqual([
    ["approval"],
    ["tab"],
    ["price"],
    ["buy_ready"],
    ["claim"],
    ["pre_buy"],
    ["buy"],
    ["layer"],
    ["buy_now"],
    ["sheet"],
    ["done"],
  ]);
});

for (const [name, config, args, approvalArgs, expected] of [
  [
    "실행 원문이 인자와 다름",
    { product: PLAIN_PRODUCT },
    PLAIN_ARGS,
    { ...PLAIN_ARGS, expectedTotalKrw: 1 },
    ["approval.failed", "naver_shopping_claim_rejected"],
  ],
  [
    "새 탭을 열지 못함",
    { product: PLAIN_PRODUCT, newTabStatus: 502 },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["tab.failed", "naver_shopping_browser_unreachable"],
  ],
  [
    "다시 계산한 금액이 승인보다 큼",
    { product: { ...PLAIN_PRODUCT, salePrice: 600 } },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["price.failed", "naver_shopping_price_changed"],
  ],
  [
    "claim 이 거절됨",
    { product: PLAIN_PRODUCT, claimStatus: 409 },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["claim.failed", "naver_shopping_claim_rejected"],
  ],
  [
    "누르기 전 탭 목록을 읽지 못함",
    { product: PLAIN_PRODUCT, listStatus: 502 },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["pre_buy.failed", "naver_shopping_browser_unreachable"],
  ],
  [
    "누르기 전부터 바로구매가 보임",
    { product: PLAIN_PRODUCT, buyNowOnProduct: true },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["pre_buy.failed", "naver_shopping_checkout_failed"],
  ],
  [
    "구매하기 단추가 끝내 그려지지 않음",
    { product: PLAIN_PRODUCT, noBuyButton: true },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["buy_ready.failed", "naver_shopping_checkout_failed"],
  ],
  [
    "구매하기 뒤 층도 주문서도 없음",
    { product: PLAIN_PRODUCT, buy: "nothing" },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["buy.unknown", "not_seen"],
  ],
  [
    "층에서 옵션을 고르지 못함",
    {
      product: OPTION_PRODUCT,
      productAfterOpen: OPENED_PRODUCT,
      layerOptions: 2,
      layerValues: ["검정"],
    },
    OPTION_ARGS,
    OPTION_ARGS,
    ["layer.failed", "naver_shopping_checkout_failed"],
  ],
  [
    "바로구매 뒤 주문서가 열리지 않음",
    { product: PLAIN_PRODUCT, buyNow: "nothing" },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["buy_now.unknown", "sheet_not_seen"],
  ],
  [
    "주문서 금액을 읽지 못함",
    { product: PLAIN_PRODUCT, amounts: { total: null, summary: null } },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["sheet.unknown", "amount_unread"],
  ],
  [
    "주문서 금액 식이 CDP 오류로 끝남",
    { product: PLAIN_PRODUCT, amountError: true },
    PLAIN_ARGS,
    PLAIN_ARGS,
    ["sheet.unknown", "naver_shopping_unavailable"],
  ],
] as const) {
  test(`${name}이면 끝 줄이 ${expected.join(" ")} 이다`, async () => {
    await fill(config as ShopConfig, args, approvalArgs);
    expect(last()).toEqual([...expected]);
    // 끝 줄은 한 번만 남는다.
    expect(
      stages().filter(([stage]) => /\.(failed|unknown)$|^done$/.test(stage)),
    ).toHaveLength(1);
  });
}

test("알 수 없는 예외는 예외 종류 이름만 남기고 메시지를 싣지 않는다", async () => {
  const work = (async () => {
    throw new TypeError(`${PRODUCT_URL} 합성 상품`);
  }) as typeof workInTab;
  const { body } = await fill(
    { product: PLAIN_PRODUCT },
    PLAIN_ARGS,
    PLAIN_ARGS,
    work,
  );
  expect(body).toEqual({ error: { code: "NAVER_SHOPPING_UNAVAILABLE" } });
  expect(last()).toEqual(["tab.failed", "typeerror"]);
  expect(written.join("")).not.toContain("합성");
});

test("진단 줄에 주소, ticket, 화면 글이 섞이지 않는다", async () => {
  const runs: Array<[ShopConfig, Record<string, unknown>]> = [
    [{ product: PLAIN_PRODUCT }, PLAIN_ARGS],
    [{ product: PLAIN_PRODUCT, listStatus: 502 }, PLAIN_ARGS],
    [{ product: PLAIN_PRODUCT, buy: "nothing" }, PLAIN_ARGS],
    [
      {
        product: OPTION_PRODUCT,
        productAfterOpen: OPENED_PRODUCT,
        layerOptions: 2,
        layerValues: ["검정"],
      },
      OPTION_ARGS,
    ],
  ];
  for (const [config, args] of runs) {
    const { ticket } = await fill(config, args);
    const text = written.join("");
    for (const secret of [
      ticket,
      ticket.split(".")[0]!,
      shop!.url,
      shop!.claimUrl,
      PRODUCT_URL,
      "naver.com",
      "://",
      "합성",
      "검정",
    ])
      expect(text).not.toContain(secret);
    shop?.stop();
    shop = null;
    forgetLeftTabs();
    written = [];
  }
});
