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
    href?: string;
    /** 캡차 선택자에 걸리는 요소. 크기와 visibility 만 흉내 낸다. */
    captchaElements?: { width: number; height: number; hidden?: boolean }[];
  },
) {
  const document = {
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

test("검색 식은 __NEXT_DATA__ 의 상품 목록만 JSON 글로 꺼내고, 없으면 null 이다", () => {
  const list = [{ item: { productTitle: "a" } }];
  expect(
    JSON.parse(
      run(SEARCH_STATE, {
        nextData: {
          props: { pageProps: { initialState: { products: { list } } } },
        },
      }),
    ),
  ).toEqual(list);
  expect(run(SEARCH_STATE, {})).toBeNull();
  expect(run(SEARCH_STATE, { nextData: { props: {} } })).toBeNull();
});

test("묶음 식은 묶음 상태를 꺼낸다", () => {
  const catalog = { info: { productName: "묶음" } };
  expect(
    JSON.parse(
      run(CATALOG_STATE, {
        nextData: { props: { pageProps: { initialState: { catalog } } } },
      }),
    ),
  ).toEqual(catalog);
});

test("상품 식은 상태가 있는지와 상품을 함께 주고, 상태가 없으면 null 이다", () => {
  expect(
    JSON.parse(
      run(PRODUCT_STATE, { preloaded: { product: { A: { name: "x" } } } }),
    ),
  ).toEqual({ present: true, value: { name: "x" } });
  expect(
    JSON.parse(run(PRODUCT_STATE, { preloaded: { product: {} } })),
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
