import { expect, test } from "bun:test";
import {
  CATALOG_STATE,
  PAGE_PROBE,
  PRODUCT_STATE,
  SEARCH_STATE,
} from "../src/expressions.ts";

/** 탭 안에서 도는 식을 가짜 `document`, `window`, `location` 으로 돌린다. 실제 네이버 화면이 아니다. */
function run(
  expression: string,
  page: {
    nextData?: unknown;
    preloaded?: unknown;
    /** `document.scripts` 의 글. 없으면 스크립트가 없는 화면이다. */
    scripts?: string[];
    href?: string;
    /** 캡차 선택자에 걸리는 요소. 크기와 visibility 만 흉내 낸다. */
    captchaElements?: { width: number; height: number; hidden?: boolean }[];
  },
) {
  const document = {
    scripts: (page.scripts ?? []).map((textContent) => ({ textContent })),
    getElementById: (id: string) =>
      id === "__NEXT_DATA__" && page.nextData !== undefined
        ? { textContent: JSON.stringify(page.nextData) }
        : null,
    querySelectorAll: () =>
      (page.captchaElements ?? []).map((e) => ({
        getBoundingClientRect: () => ({ width: e.width, height: e.height }),
        hidden: e.hidden === true,
      })),
  };
  const getComputedStyle = (e: { hidden: boolean }) => ({
    visibility: e.hidden ? "hidden" : "visible",
  });
  const window = { __PRELOADED_STATE__: page.preloaded };
  const location = {
    href: page.href ?? "https://search.shopping.naver.com/ns/search",
  };
  return new Function(
    "document",
    "window",
    "location",
    "getComputedStyle",
    `return ${expression};`,
  )(document, window, location, getComputedStyle);
}

/** 합성 RSC 조각 스크립트. 실제 네이버 화면을 옮긴 것이 아니다. */
function chunk(piece: string) {
  return `self.__next_f.push(${JSON.stringify([1, piece])})`;
}

/** 합성 상품 객체 글. `extra` 는 그대로 덧붙는다. */
function productJson(
  id: string,
  extra: Record<string, unknown> = {},
  name = `합성 상품 ${id}`,
) {
  return JSON.stringify({
    productName: name,
    salePrice: 5000,
    discountedSalePrice: 4500,
    mallName: `합성몰 ${id}`,
    productUrl: {
      pcUrl: `https://smartstore.naver.com/shop${id}/products/${id}`,
      mobileUrl: `https://m.smartstore.naver.com/shop${id}/products/${id}`,
    },
    nvMid: `mid${id}`,
    channelProductId: `ch${id}`,
    averageReviewScore: 4.5,
    totalReviewCount: 12,
    clickUrl: "https://ader.naver.com/x",
    ...extra,
  });
}

function searchResult(scripts: string[]) {
  const raw = run(SEARCH_STATE, { scripts });
  return raw === null ? null : JSON.parse(raw);
}

test("검색 식은 조각 스크립트를 이어 productName 객체의 필요한 칸만 JSON 글로 꺼낸다", () => {
  const row = `1a:["$","div",null,{"children":[${productJson("1", { cardType: "AD", adId: "ad1" })},${productJson("2")}]}]\n`;
  expect(searchResult([chunk(row)])).toEqual([
    {
      productName: "합성 상품 1",
      salePrice: 5000,
      discountedSalePrice: 4500,
      mallName: "합성몰 1",
      productUrl: { pcUrl: "https://smartstore.naver.com/shop1/products/1" },
      nvMid: "mid1",
      adId: "ad1",
      cardType: "AD",
    },
    {
      productName: "합성 상품 2",
      salePrice: 5000,
      discountedSalePrice: 4500,
      mallName: "합성몰 2",
      productUrl: { pcUrl: "https://smartstore.naver.com/shop2/products/2" },
      nvMid: "mid2",
    },
  ]);
});

test("검색 식은 조각 경계에서 객체가 나뉘어도 이은 뒤에 한 번만 꺼낸다", () => {
  const row = `1a:[${productJson("1")},${productJson("2")}]\n`;
  // 문자열 값 한가운데와 객체 한가운데에서 자른다.
  const first = row.indexOf("합성몰 1") + 2;
  const second = row.indexOf('"nvMid":"mid2"');
  const result = searchResult([
    chunk(row.slice(0, first)),
    "self.__next_f.push([0])",
    chunk(row.slice(first, second)),
    chunk(row.slice(second)),
  ]);
  expect(result.map((p: { nvMid: string }) => p.nvMid)).toEqual([
    "mid1",
    "mid2",
  ]);
});

test("검색 식은 문자열 안의 중괄호와 따옴표, 조각이 아닌 줄과 다른 스크립트에 흔들리지 않는다", () => {
  const rows = [
    `2b:T10,따옴표 " 와 { 가 섞인 글\n`,
    `1a:[${productJson("1", {}, '합성 {상품} "인용"')}]\n`,
    `3c:["$","p",null,{"productNameLabel":"상품 이름"}]\n`,
  ].join("");
  const result = searchResult([
    "window.dataLayer = [];",
    chunk(rows),
    "self.__next_f.push(",
  ]);
  expect(result).toHaveLength(1);
  expect(result[0].productName).toBe('합성 {상품} "인용"');
});

test("검색 식은 줄바꿈 없이 길이로 끝나는 텍스트 행의 짝 없는 따옴표와 괄호가 바로 뒤 상품 행을 망가뜨리지 않는다", () => {
  // T 행의 길이는 글자 수가 아니라 UTF-8 바이트 수다. 한글이 섞인 글로 본다.
  const body = `짝 없는 " 따옴표 { 괄호 한글`;
  const textRow = `2b:T${Buffer.byteLength(body).toString(16)},${body}`;
  const row = `${textRow}1a:[${productJson("1")},${productJson("2")}]\n`;
  const result = searchResult([chunk(row)]);
  expect(result.map((p: { nvMid: string }) => p.nvMid)).toEqual([
    "mid1",
    "mid2",
  ]);
  // 글이 상품 행 한가운데에서 이어지는 조각이어도 같다.
  const cut = row.indexOf("합성몰 1");
  expect(
    searchResult([chunk(row.slice(0, cut)), chunk(row.slice(cut))]),
  ).toHaveLength(2);
});

test("검색 식은 상품 객체를 200개까지만 준다", () => {
  const row = `1a:[${Array.from({ length: 250 }, (_, i) => productJson(String(i))).join(",")}]\n`;
  expect(searchResult([chunk(row)])).toHaveLength(200);
});

test("검색 식은 조각 스크립트가 없으면 null, 상품이 없으면 빈 배열이다", () => {
  expect(run(SEARCH_STATE, {})).toBeNull();
  expect(run(SEARCH_STATE, { scripts: ["var a = 1;"] })).toBeNull();
  expect(searchResult([chunk('1a:["$","div",null,{}]\n')])).toEqual([]);
});

/** 합성 묶음 객체 글. 실제 상품 이름과 값이 아니다. */
function catalogJson(extra: Record<string, unknown> = {}) {
  return JSON.stringify({
    nCatalogId: "1000",
    productName: "합성 묶음",
    lowestPrice: 9900,
    deliveryFee: 2500,
    lowestPriceWithFee: 12400,
    differentialDeliveryFeeContent: "제주 추가 3,000원",
    categoryId: "50000001",
    reviewCount: 7,
    ...extra,
  });
}

/** 같은 조각에 실리는 광고·추천 상품 객체. 묶음 키(`nCatalogId`, `lowestPrice`)가 없다. */
function adJson(id: string) {
  return JSON.stringify({
    productName: `합성 광고 ${id}`,
    price: 7000,
    mallName: `합성몰 ${id}`,
    deliveryFee: 3000,
    isAd: true,
    recInfo: '{"area":"CATALOG"}',
  });
}

/** 묶음 식이 준 배열. 조각 스크립트가 없으면 null 이다. */
function catalogResult(scripts: string[]) {
  const raw = run(CATALOG_STATE, { scripts });
  return raw === null ? null : JSON.parse(raw);
}

const CATALOG_EXPECTED = {
  nCatalogId: "1000",
  productName: "합성 묶음",
  lowestPrice: 9900,
  deliveryFee: 2500,
  lowestPriceWithFee: 12400,
  differentialDeliveryFeeContent: "제주 추가 3,000원",
  categoryId: "50000001",
};

test("묶음 식은 조각을 이어 nCatalogId 와 lowestPrice 를 가진 객체 하나의 필요한 칸만 꺼낸다", () => {
  const row = `1a:["$","div",null,{"children":[${adJson("1")},${catalogJson()},${adJson("2")}]}]\n`;
  expect(catalogResult([chunk(row)])).toEqual([CATALOG_EXPECTED]);
});

test("묶음 식은 조각 경계에서 객체가 나뉘어도 이은 뒤에 꺼낸다", () => {
  const row = `1a:[${adJson("1")},${catalogJson()}]\n`;
  const first = row.indexOf("합성 묶음") + 2;
  const second = row.indexOf('"deliveryFee":2500');
  expect(
    catalogResult([
      chunk(row.slice(0, first)),
      "self.__next_f.push([0])",
      chunk(row.slice(first, second)),
      chunk(row.slice(second)),
    ]),
  ).toEqual([CATALOG_EXPECTED]);
});

test("묶음 식은 앞의 텍스트 행에 짝 없는 따옴표와 괄호가 있어도 뒤 묶음 행을 읽는다", () => {
  const body = `짝 없는 " 따옴표 { 괄호 한글`;
  const textRow = `2b:T${Buffer.byteLength(body).toString(16)},${body}`;
  expect(
    catalogResult([chunk(`${textRow}1a:[${adJson("1")},${catalogJson()}]\n`)]),
  ).toEqual([CATALOG_EXPECTED]);
});

test("묶음 식은 두 키 가운데 하나만 가진 객체를 묶음으로 보지 않고, 없으면 null 이다", () => {
  const onlyPrice = JSON.stringify({ productName: "합성", lowestPrice: 1 });
  const onlyId = JSON.stringify({ nCatalogId: "1", productName: "합성" });
  expect(
    catalogResult([chunk(`1a:[${adJson("1")},${onlyPrice},${onlyId}]\n`)]),
  ).toEqual([]);
  expect(run(CATALOG_STATE, {})).toBeNull();
  expect(run(CATALOG_STATE, { scripts: ["var a = 1;"] })).toBeNull();
});

test("묶음 식은 묶음 객체가 값이 비어 있어도 칸만 있으면 꺼내 parse 가 거르게 한다", () => {
  const row = `1a:[${catalogJson({ lowestPrice: null, productName: "합성" })}]\n`;
  expect(catalogResult([chunk(row)])).toMatchObject([{ lowestPrice: null }]);
});

test("묶음 식은 비교·추천 묶음이 앞서 실려도 차례대로 모두 주고 20개까지만 준다", () => {
  const other = (id: string) =>
    catalogJson({ nCatalogId: id, productName: `합성 추천 ${id}` });
  const row = `1a:[${other("2000")},${other("3000")},${catalogJson()}]\n`;
  expect(
    catalogResult([chunk(row)]).map(
      (c: { nCatalogId: string }) => c.nCatalogId,
    ),
  ).toEqual(["2000", "3000", "1000"]);
  const many = `1a:[${Array.from({ length: 30 }, (_, i) => other(String(i))).join(",")}]\n`;
  expect(catalogResult([chunk(many)])).toHaveLength(20);
});

test("상품 식은 상태가 있는지와 상품을 함께 주고, 상태가 없으면 null 이다", () => {
  expect(
    JSON.parse(
      run(PRODUCT_STATE, {
        preloaded: {
          product: { salePrice: 0 },
          simpleProductForDetailPage: { name: "x" },
        },
      }),
    ),
  ).toEqual({ present: true, value: { name: "x" } });
  // 비어 있는 product 칸만 있으면 상품이 없는 것이다.
  expect(
    JSON.parse(
      run(PRODUCT_STATE, { preloaded: { product: { salePrice: 0 } } }),
    ),
  ).toEqual({ present: true, value: null });
  expect(run(PRODUCT_STATE, {})).toBeNull();
});

test("화면 확인 식은 주소와 보이는 캡차 요소의 있고 없음을 준다", () => {
  const href = "https://nid.naver.com/nidlogin.login";
  expect(
    run(PAGE_PROBE, { href, captchaElements: [{ width: 300, height: 200 }] }),
  ).toEqual({ href, captcha: true });
});

test("숨은 script#ncaptcha-script 와 숨은 captcha 틀만 있는 정상 화면은 캡차가 아니다", () => {
  const hiddenOnly = [
    { width: 0, height: 0 },
    { width: 300, height: 0 },
    { width: 300, height: 200, hidden: true },
  ];
  expect(run(PAGE_PROBE, { captchaElements: hiddenOnly }).captcha).toBe(false);
  expect(run(PAGE_PROBE, {}).captcha).toBe(false);
  // 숨은 것 옆에 보이는 #captcha_wrap 이 하나라도 있으면 캡차다.
  expect(
    run(PAGE_PROBE, {
      captchaElements: [...hiddenOnly, { width: 400, height: 300 }],
    }).captcha,
  ).toBe(true);
});
