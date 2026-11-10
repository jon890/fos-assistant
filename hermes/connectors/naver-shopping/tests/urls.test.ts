import { expect, test } from "bun:test";
import { classifyPage, humanStepError } from "../src/classify.ts";
import { classifyUrl, searchUrl } from "../src/urls.ts";

test("스마트스토어와 브랜드스토어 상품 주소는 store 이고 조회 글을 뗀다", () => {
  expect(classifyUrl("https://smartstore.naver.com/shop_a/products/123?NaPm=x#top")).toEqual({
    kind: "store",
    url: "https://smartstore.naver.com/shop_a/products/123",
  });
  expect(classifyUrl("https://m.brand.naver.com/brand-b/products/9")?.kind).toBe("store");
});

test("가격비교 묶음 주소는 catalog 다", () => {
  expect(classifyUrl("https://search.shopping.naver.com/catalog/555?query=x")).toEqual({
    kind: "catalog",
    url: "https://search.shopping.naver.com/catalog/555",
  });
});

test("그 밖의 https 주소와 상품 경로가 아닌 네이버 주소는 external 이다", () => {
  expect(classifyUrl("https://www.11st.co.kr/products/1")?.kind).toBe("external");
  expect(classifyUrl("https://cr.shopping.naver.com/adcr.nhn?x=1")?.kind).toBe("external");
  expect(classifyUrl("https://smartstore.naver.com/shop_a")?.kind).toBe("external");
});

test("https 가 아니거나 계정·포트가 있거나 길거나 공백이 있으면 거절한다", () => {
  for (const raw of [
    "http://smartstore.naver.com/a/products/1",
    "javascript:alert(1)",
    "https://user:pw@smartstore.naver.com/a/products/1",
    "https://smartstore.naver.com:8443/a/products/1",
    `https://smartstore.naver.com/a/products/1?${"x".repeat(2100)}`,
    "https://smartstore.naver.com/a /products/1",
    "",
    42,
  ])
    expect(classifyUrl(raw)).toBeNull();
});

test("검색 주소는 검색어만 싣고 페이지 인자가 없다", () => {
  const url = new URL(searchUrl("물티슈 100매"));
  expect(url.hostname).toBe("search.shopping.naver.com");
  expect([...url.searchParams.keys()]).toEqual(["query"]);
  expect(url.searchParams.get("query")).toBe("물티슈 100매");
});

test("화면 분류는 로그인 호스트, 기기 확인 경로, 캡차 요소를 가린다", () => {
  expect(classifyPage({ href: "https://nid.naver.com/nidlogin.login", captcha: false })).toBe("login");
  expect(classifyPage({ href: "https://nid.naver.com/login/ext/deviceConfirm", captcha: false })).toBe("device_check");
  expect(classifyPage({ href: "https://search.shopping.naver.com/ns/search", captcha: true })).toBe("captcha");
  expect(classifyPage({ href: "https://smartstore.naver.com/a/products/1", captcha: false })).toBe("ok");
  expect(humanStepError("ok")).toBeNull();
  expect(humanStepError("captcha")).toBe("NAVER_SHOPPING_CAPTCHA");
});
