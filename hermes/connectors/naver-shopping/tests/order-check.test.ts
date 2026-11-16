import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, spyOn, test } from "bun:test";
import { EXPRESSIONS, ORDER_HISTORY_STATE } from "../src/expressions.ts";
import { checkOrder, type OrderCheckDeps } from "../src/order-check.ts";
import {
  ORDER_TIME_GRACE_MS,
  parsePaymentContext,
} from "../src/parse-orders.ts";
import { createServer } from "../src/server.ts";
import { BROWSER_URL_ENV } from "../src/session.ts";
import { ORDER_HISTORY_URL } from "../src/urls.ts";

const ENV = {
  [BROWSER_URL_ENV]: "http://127.0.0.1:9/gateway-token-0123456789",
};

/** 넘긴 시각. 합성 값이다. */
const HANDED_OFF = Date.UTC(2026, 10, 16, 5, 0, 0);
const PRODUCT_NO = "12345678901";

/** fos-ctx 가 넣는 결제 넘기기 맥락. */
function payment(extra: Record<string, unknown> = {}) {
  return {
    v: 1,
    handed_off_at_ms: HANDED_OFF,
    final_amount_krw: 3300,
    expected_total_krw: 3300,
    url: `https://smartstore.naver.com/some-store/products/${PRODUCT_NO}`,
    ...extra,
  };
}

/** 화면 글로 남으면 안 되는 합성 값. 결과 글에 하나도 없어야 한다. */
const SCREEN_TEXT = [
  "합성수령인",
  "합성시 합성구 합성로 1",
  "합성 상품 이름",
  "합성 판매처",
  "2026111600000001",
];

/** 결제 내역 항목 하나(2026-11-16 실측 칸 꼴의 합성 값). 실제 네이버 화면을 옮긴 것이 아니다. */
function item(extra: Record<string, unknown> = {}) {
  return {
    _id: "synthetic-id",
    serviceType: "ORDER",
    status: { name: "PAYMENT_COMPLETED", text: "결제완료", color: "green" },
    merchantNo: "1",
    merchantName: SCREEN_TEXT[3],
    product: {
      no: PRODUCT_NO,
      name: SCREEN_TEXT[2],
      imgUrl: "https://example.invalid/i.png",
      infoUrl: `https://smartstore.naver.com/main/products/${PRODUCT_NO}`,
      price: 3300,
      restAmount: 0,
      isGift: false,
    },
    buttons: [],
    date: HANDED_OFF + 60_000,
    productDetailUrl: `https://smartstore.naver.com/main/products/${PRODUCT_NO}`,
    orderDetailUrl: `https://orders.pay.naver.com/order/status/${SCREEN_TEXT[4]}?returnUrl=x`,
    additionalData: {
      orderAmount: 3300,
      orderNo: SCREEN_TEXT[4],
      productOrderNo: SCREEN_TEXT[4],
      orderQuantity: 1,
      paymentMethod: "합성",
      receiverName: SCREEN_TEXT[0],
      address: SCREEN_TEXT[1],
    },
    ...extra,
  };
}

/** `__NEXT_DATA__` 꼴. `PAYMENT_LIST` 줄의 `state.data.pages[].items[]` 가 결제 목록이다. */
function nextData(items: unknown[], queryName = "PAYMENT_LIST") {
  return {
    props: {
      pageProps: {
        dehydratedState: {
          queries: [
            { queryKey: ["USER"], state: { data: { name: SCREEN_TEXT[0] } } },
            {
              queryKey: [queryName, "1"],
              state: {
                data: {
                  pages: [
                    {
                      type: "x",
                      success: true,
                      items,
                      itemCount: items.length,
                      totalPage: 1,
                      curPage: 1,
                    },
                  ],
                },
              },
            },
          ],
        },
      },
    },
  };
}

/** 고정 식을 가짜 `document` 로 돌린다. */
function runHistory(data: unknown) {
  const document = {
    getElementById: (id: string) =>
      id === "__NEXT_DATA__" && data !== undefined
        ? { textContent: JSON.stringify(data) }
        : null,
  };
  return new Function("document", `return ${ORDER_HISTORY_STATE};`)(
    document,
  ) as unknown;
}

/** 탭 읽기를 대신한다. 가짜 화면에서 고정 식이 낸 글을 `parse` 에 준다. */
function history(data: unknown) {
  const opened: { url: string; expression: string }[] = [];
  const deps: OrderCheckDeps = {
    read: async (_cdp, url, expression, parse) => {
      opened.push({ url, expression });
      return parse(runHistory(data));
    },
  };
  return { opened, deps };
}

let stderr: ReturnType<typeof spyOn> | null = null;
afterEach(() => {
  stderr?.mockRestore();
  stderr = null;
});

test("넘긴 뒤의 같은 상품 주문이 최종 금액과 같으면 확인됐다고 답하고 상수 주소 하나만 연다", async () => {
  const { opened, deps } = history(nextData([item()]));
  const result = await checkOrder(ENV, payment(), deps);
  expect(result).toMatchObject({
    status: "CONFIRMED",
    final_amount_krw: 3300,
    order: {
      status: "PAYMENT_COMPLETED",
      paid_amount_krw: 3300,
      ordered_at: new Date(HANDED_OFF + 60_000).toISOString(),
    },
  });
  expect(opened).toEqual([
    { url: ORDER_HISTORY_URL, expression: ORDER_HISTORY_STATE },
  ]);
  expect(EXPRESSIONS.has(ORDER_HISTORY_STATE)).toBe(true);
});

test("결과와 고정 식이 꺼낸 글에 이름, 주소, 상품명, 판매처, 주문 번호가 없다", async () => {
  const raw = String(runHistory(nextData([item()])));
  const result = JSON.stringify(
    await checkOrder(ENV, payment(), history(nextData([item()])).deps),
  );
  for (const value of SCREEN_TEXT) {
    expect(result).not.toContain(value);
    expect(raw).not.toContain(value);
  }
  expect(Object.keys(JSON.parse(raw)[0]).sort()).toEqual([
    "amounts",
    "date",
    "productNo",
    "productUrl",
    "serviceType",
    "status",
  ]);
});

test("주문이 없으면 결제가 확인되지 않았다고 답한다", async () => {
  expect(
    await checkOrder(ENV, payment(), history(nextData([])).deps),
  ).toMatchObject({ status: "NOT_FOUND", final_amount_krw: 3300 });
});

test("넘긴 시각보다 여유 시간 넘게 앞선 같은 상품 주문은 보지 않는다", async () => {
  const old = item({ date: HANDED_OFF - ORDER_TIME_GRACE_MS - 1 });
  expect(
    await checkOrder(ENV, payment(), history(nextData([old])).deps),
  ).toMatchObject({ status: "NOT_FOUND" });
  const nearly = item({ date: HANDED_OFF - ORDER_TIME_GRACE_MS });
  expect(
    await checkOrder(ENV, payment(), history(nextData([nearly])).deps),
  ).toMatchObject({ status: "CONFIRMED" });
});

test("상품은 맞는데 금액 후보가 모두 다르면 금액이 다르다고 답하고 첫 후보를 싣는다", async () => {
  const other = item({
    product: { ...item().product, price: 3000 },
    additionalData: { ...item().additionalData, orderAmount: 2900 },
  });
  expect(
    await checkOrder(ENV, payment(), history(nextData([other])).deps),
  ).toMatchObject({
    status: "AMOUNT_MISMATCH",
    final_amount_krw: 3300,
    order: { paid_amount_krw: 2900 },
  });
});

test("금액 후보 하나만 같아도 맞다고 본다(배송비가 어느 칸인지는 가설)", async () => {
  const onlyPrice = item({
    additionalData: { ...item().additionalData, orderAmount: 400 },
  });
  expect(
    await checkOrder(ENV, payment(), history(nextData([onlyPrice])).deps),
  ).toMatchObject({ status: "CONFIRMED", order: { paid_amount_krw: 3300 } });
});

test("다른 상품과 ORDER 가 아닌 항목은 보지 않고, 맞는 주문이 여럿이면 금액이 맞는 것을 고른다", async () => {
  const otherProduct = item({
    product: { ...item().product, no: "999", infoUrl: "x" },
  });
  const simple = item({ serviceType: "SIMPLE_PAYMENT" });
  expect(
    await checkOrder(
      ENV,
      payment(),
      history(nextData([otherProduct, simple])).deps,
    ),
  ).toMatchObject({ status: "NOT_FOUND" });
  const mismatchNewer = item({
    date: HANDED_OFF + 120_000,
    product: { ...item().product, price: 1 },
    additionalData: { ...item().additionalData, orderAmount: 1 },
  });
  expect(
    await checkOrder(
      ENV,
      payment(),
      history(nextData([mismatchNewer, item()])).deps,
    ),
  ).toMatchObject({ status: "CONFIRMED" });
});

test("product.no 가 없으면 상품 주소의 번호로 맞춘다", async () => {
  const noNumber = item({ product: { ...item().product, no: undefined } });
  expect(
    await checkOrder(ENV, payment(), history(nextData([noNumber])).deps),
  ).toMatchObject({ status: "CONFIRMED" });
});

test("결제 목록 칸이 없거나 ORDER 항목을 하나도 읽지 못하면 PAGE_CHANGED 이고 진단 줄에 칸 이름만 남긴다", async () => {
  stderr = spyOn(process.stderr, "write").mockImplementation(() => true);
  for (const data of [undefined, nextData([item()], "OTHER"), { props: {} }]) {
    await expect(
      checkOrder(ENV, payment(), history(data).deps),
    ).rejects.toMatchObject({ code: "NAVER_SHOPPING_PAGE_CHANGED" });
  }
  const broken = item({ date: "어제", product: { name: SCREEN_TEXT[2] } });
  await expect(
    checkOrder(ENV, payment(), history(nextData([broken])).deps),
  ).rejects.toMatchObject({ code: "NAVER_SHOPPING_PAGE_CHANGED" });
  const lines = stderr.mock.calls
    .map((call: unknown[]) => String(call[0]))
    .join("");
  expect(lines).toContain('"missing":["orders"]');
  expect(lines).toContain('"missing":["order_item"]');
  for (const value of SCREEN_TEXT) expect(lines).not.toContain(value);
});

test("맥락이 없거나 모양이 틀리면 화면을 열지 않고 NO_PAYMENT_HANDOFF 다", async () => {
  const bad = [
    undefined,
    null,
    "x",
    payment({ v: 2 }),
    payment({ extra: 1 }),
    payment({ final_amount_krw: -1 }),
    payment({ handed_off_at_ms: "1" }),
    payment({ url: "https://www.example-mall.com/products/1" }),
    payment({ url: "https://search.shopping.naver.com/catalog/1" }),
  ];
  for (const value of bad) {
    const { opened, deps } = history(nextData([item()]));
    expect(await checkOrder(ENV, value, deps)).toMatchObject({
      status: "NO_PAYMENT_HANDOFF",
    });
    expect(opened).toHaveLength(0);
  }
  expect(parsePaymentContext(payment())).toEqual({
    handedOffAtMs: HANDED_OFF,
    finalAmountKrw: 3300,
    expectedTotalKrw: 3300,
    productNo: PRODUCT_NO,
  });
});

test("서버 도구는 _fos_payment 를 넘겨받고, 모델 인자가 없으면 화면 없이 NO_PAYMENT_HANDOFF 다", async () => {
  const seen: unknown[] = [];
  const client = new Client({ name: "order-check-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  await createServer(ENV, {
    read: async () => {
      throw new Error("다른 도구");
    },
    orders: {
      read: async (_cdp, _url, _expression, parse) => {
        seen.push(_url);
        return parse(runHistory(nextData([item()])));
      },
    },
  }).connect(serverTransport);
  await client.connect(clientTransport);
  const none = await client.callTool({
    name: "check_shopping_order",
    arguments: {},
  });
  expect(
    JSON.parse((none.content as { text: string }[])[0].text),
  ).toMatchObject({
    status: "NO_PAYMENT_HANDOFF",
  });
  expect(seen).toHaveLength(0);
  const done = await client.callTool({
    name: "check_shopping_order",
    arguments: { _fos_payment: payment() },
  });
  expect(
    JSON.parse((done.content as { text: string }[])[0].text),
  ).toMatchObject({
    status: "CONFIRMED",
  });
  expect(seen).toEqual([ORDER_HISTORY_URL]);
});

test("확인되지 않으면 진단 줄에 결과 낱말과 까닭 낱말(product, time, amount)만 남긴다", async () => {
  stderr = spyOn(process.stderr, "write").mockImplementation(() => true);
  const cases: [unknown[], string, string][] = [
    [[], "match.not_found", "product"],
    [
      [item({ date: HANDED_OFF - ORDER_TIME_GRACE_MS - 1 })],
      "match.not_found",
      "time",
    ],
    [
      [
        item({
          product: { ...item().product, price: 1 },
          additionalData: { orderAmount: 2 },
        }),
      ],
      "match.amount_mismatch",
      "amount",
    ],
  ];
  for (const [items, stage, reason] of cases) {
    stderr.mockClear();
    await checkOrder(ENV, payment(), history(nextData(items)).deps);
    const line = stderr.mock.calls
      .map((call: unknown[]) => String(call[0]))
      .join("");
    expect(line).toContain(`"stage":"${stage}"`);
    expect(line).toContain(`"missing":["${reason}"]`);
    expect(line).not.toContain("3300");
    expect(line).not.toContain(PRODUCT_NO);
  }
});
