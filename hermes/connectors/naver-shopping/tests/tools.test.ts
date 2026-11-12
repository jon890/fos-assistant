import { afterEach, expect, test } from "bun:test";
import {
  CATALOG_STATE,
  PRODUCT_STATE,
  SEARCH_STATE,
} from "../src/expressions.ts";
import { BROWSER_URL_ENV } from "../src/session.ts";
import { productDetail, searchProducts, type ToolDeps } from "../src/tools.ts";
import { startFakeBrowser, type FakeBrowser } from "./fake-browser.ts";

const ENV = {
  [BROWSER_URL_ENV]: "http://127.0.0.1:9/gateway-token-0123456789",
};

/** 탭 읽기를 대신한다. 연 주소와 식을 기록하고 정한 글을 돌려준다. */
function reader(answer: string | null) {
  const opened: { url: string; expression: string }[] = [];
  const deps: ToolDeps = {
    read: async (_cdp, url, expression, parse) => {
      opened.push({ url, expression });
      return parse(answer);
    },
  };
  return { opened, deps };
}

test("다른 쇼핑몰 주소는 탭을 열지 않고 바로 살 수 없다고 답한다", async () => {
  const { opened, deps } = reader(null);
  expect(
    await productDetail(ENV, "https://www.example-mall.com/p/1", deps),
  ).toEqual({
    kind: "external",
    url: "https://www.example-mall.com/p/1",
    buyable: false,
  });
  expect(opened).toHaveLength(0);
});

test("거절 주소는 탭을 열지 않고 입력 오류다", async () => {
  const { opened, deps } = reader(null);
  await expect(
    productDetail(ENV, "http://smartstore.naver.com/a/products/1", deps),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_INVALID_INPUT",
  });
  expect(opened).toHaveLength(0);
});

test("스토어 상품은 상품 식으로, 묶음은 묶음 식으로 정리한 주소를 한 번 연다", async () => {
  const store = reader(
    JSON.stringify({
      present: true,
      value: {
        name: "물티슈",
        salePrice: 1000,
        statusType: "SALE",
        productDeliveryInfo: { baseFee: 0 },
      },
    }),
  );
  const parsed = await productDetail(
    ENV,
    "https://smartstore.naver.com/a/products/1?x=1",
    store.deps,
  );
  expect(store.opened).toEqual([
    {
      url: "https://smartstore.naver.com/a/products/1",
      expression: PRODUCT_STATE,
    },
  ]);
  expect(parsed).toMatchObject({
    kind: "store",
    price_krw: 1000,
    naver_pay_buy: true,
  });

  const catalog = reader(
    JSON.stringify({ info: { productName: "묶음" }, products: [] }),
  );
  expect(
    await productDetail(
      ENV,
      "https://search.shopping.naver.com/catalog/7",
      catalog.deps,
    ),
  ).toMatchObject({
    kind: "catalog",
    sellers: [],
  });
  expect(catalog.opened).toEqual([
    {
      url: "https://search.shopping.naver.com/catalog/7",
      expression: CATALOG_STATE,
    },
  ]);
});

test("상태는 있는데 상품이 없으면 없는 상품, 상태가 없으면 바뀐 화면이다", async () => {
  await expect(
    productDetail(
      ENV,
      "https://smartstore.naver.com/a/products/1",
      reader(JSON.stringify({ present: true, value: null })).deps,
    ),
  ).rejects.toMatchObject({ code: "NAVER_SHOPPING_NOT_FOUND" });
  await expect(
    productDetail(
      ENV,
      "https://smartstore.naver.com/a/products/1",
      reader(null).deps,
    ),
  ).rejects.toMatchObject({ code: "NAVER_SHOPPING_PAGE_CHANGED" });
});

test("검색은 검색어 하나로 첫 페이지를 한 번 읽고, 목록이 없으면 바뀐 화면이다", async () => {
  const ok = reader("[]");
  expect(await searchProducts(ENV, "  물티슈  ", ok.deps)).toEqual({
    query: "물티슈",
    count: 0,
    products: [],
  });
  expect(ok.opened).toHaveLength(1);
  expect(ok.opened[0]!.expression).toBe(SEARCH_STATE);
  await expect(
    searchProducts(ENV, "물티슈", reader(null).deps),
  ).rejects.toMatchObject({ code: "NAVER_SHOPPING_PAGE_CHANGED" });
});

test("빈 검색어, 100자를 넘는 검색어, 제어 문자는 탭을 열지 않고 입력 오류다", async () => {
  for (const query of ["   ", "가".repeat(101), "a\nb"]) {
    const { opened, deps } = reader("[]");
    await expect(searchProducts(ENV, query, deps)).rejects.toMatchObject({
      code: "NAVER_SHOPPING_INVALID_INPUT",
    });
    expect(opened).toHaveLength(0);
  }
});

let browser: FakeBrowser | null = null;
afterEach(() => browser?.stop());

test("가짜 브라우저로 검색 도구를 끝까지 부르면 결과를 읽고 연 탭을 닫는다", async () => {
  browser = startFakeBrowser({
    state: JSON.stringify([
      {
        item: {
          productTitle: "물티슈",
          price: 9900,
          mallName: "가게",
          mallProductUrl: "https://smartstore.naver.com/a/products/1",
        },
      },
    ]),
  });
  const result = await searchProducts(
    { [BROWSER_URL_ENV]: browser.url },
    "물티슈",
    { read: (await import("../src/tab.ts")).readInTab, tab: { pollMs: 1 } },
  );
  expect(result.count).toBe(1);
  expect(browser.http).toContain("GET /json/close/tab-1");
});

test("가짜 브라우저로 상세를 읽다 칸이 없으면 탭을 남긴 예상 밖 화면 넘기기로 끝난다", async () => {
  browser = startFakeBrowser({ state: JSON.stringify({ present: false }) });
  await expect(
    productDetail(
      { [BROWSER_URL_ENV]: browser.url },
      "https://smartstore.naver.com/a/products/1",
      { read: (await import("../src/tab.ts")).readInTab, tab: { pollMs: 1 } },
    ),
  ).rejects.toMatchObject({
    code: "NAVER_SHOPPING_PAGE_CHANGED",
    handoff: { need: "unexpected_page", tab: "tab-1" },
  });
  expect(browser.http.some((line) => line.includes("/json/close/"))).toBe(
    false,
  );
  (await import("../src/tab.ts")).forgetLeftTabs();
});
