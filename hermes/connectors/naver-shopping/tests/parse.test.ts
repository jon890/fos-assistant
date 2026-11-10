import { expect, test } from "bun:test";
import {
  MissingFields,
  parseCatalog,
  parseStoreProduct,
} from "../src/parse-product.ts";
import { parseSearch, searchLooksChanged } from "../src/parse-search.ts";

/** 합성 검색 항목. 실제 네이버 화면을 옮긴 것이 아니다. */
function item(index: number, extra: Record<string, unknown> = {}) {
  return {
    item: {
      productTitle: `상품 ${index}`,
      price: String(1000 + index),
      mallName: `가게 ${index}`,
      mallProductUrl: `https://smartstore.naver.com/shop${index}/products/${index}`,
      ...extra,
    },
  };
}

test("검색 결과는 40개까지, 화면 차례 그대로, 광고와 네이버페이 표시를 싣는다", () => {
  const list = [
    { ...item(0), adcrUrl: "https://cr.shopping.naver.com/adcr" },
    item(1, { npayYn: "Y" }),
    ...Array.from({ length: 43 }, (_, i) => item(i + 2)),
  ];
  const products = parseSearch(list)!;
  expect(products).toHaveLength(40);
  expect(products[0]).toMatchObject({
    rank: 1,
    title: "상품 0",
    price_krw: 1000,
    ad: true,
    naver_pay: "unknown",
    kind: "store",
  });
  expect(products[1]).toMatchObject({ rank: 2, ad: false, naver_pay: "yes" });
});

test("거절 주소와 칸이 빠진 항목은 빼고, 바깥 쇼핑몰은 external 로 둔다", () => {
  const products = parseSearch([
    item(1, { mallProductUrl: "http://smartstore.naver.com/a/products/1" }),
    item(2, { price: undefined, lowPrice: undefined }),
    item(3, { mallProductUrl: "https://www.example-mall.com/p/3" }),
  ])!;
  expect(products.map((product) => [product.title, product.kind])).toEqual([
    ["상품 3", "external"],
  ]);
});

test("목록이 배열이 아니면 null, 항목이 있는데 하나도 못 읽으면 바뀐 화면이다", () => {
  expect(parseSearch({ list: [] })).toBeNull();
  const unreadable = [{ item: { name: "다른 모양" } }];
  expect(searchLooksChanged(unreadable, parseSearch(unreadable)!)).toBe(true);
  expect(searchLooksChanged([], parseSearch([])!)).toBe(false);
});

const product = {
  name: "물티슈 100매",
  channel: { channelName: "좋은가게" },
  salePrice: 12000,
  benefitsView: { discountedSalePrice: 10900 },
  statusType: "SALE",
  options: [{ groupName: "수량" }, { groupName: "향" }],
  optionCombinations: [
    { optionName1: "10팩", optionName2: "무향", price: 0, stockQuantity: 3 },
    { optionName1: "10팩", optionName2: "레몬", price: 500, stockQuantity: 0 },
    { optionName1: "20팩", optionName2: "무향", price: 9000, stockQuantity: 0 },
  ],
  productDeliveryInfo: {
    baseFee: 3000,
    freeConditionalAmount: 30000,
    deliveryFeeByArea: { jeju: 3000 },
  },
};

test("상품은 즉시할인 판매가, 첫 묶음 값의 추가 금액과 품절, 배송비를 싣는다", () => {
  const parsed = parseStoreProduct(
    product,
    "https://smartstore.naver.com/a/products/1",
  )!;
  expect(parsed.price_krw).toBe(10900);
  expect(parsed.store).toBe("좋은가게");
  expect(parsed.options[0]).toEqual({
    name: "수량",
    values: [
      { label: "10팩", extra_krw: 0, sold_out: false },
      { label: "20팩", extra_krw: 9000, sold_out: true },
    ],
  });
  expect(
    parsed.options[1]!.values.map((value) => [value.label, value.extra_krw]),
  ).toEqual([
    ["무향", null],
    ["레몬", null],
  ]);
  expect(parsed.shipping).toEqual({
    fee_krw: 3000,
    free_over_krw: 30000,
    regional_extra: true,
  });
  expect(parsed.naver_pay_buy).toBe(true);
  expect("options_truncated" in parsed).toBe(false);
});

test("판매 중이 아니면 바로 살 수 없고, 옵션 값이 100개를 넘으면 잘랐다고 알린다", () => {
  const many = Array.from({ length: 120 }, (_, i) => ({
    optionName1: `값 ${i}`,
    price: 0,
    stockQuantity: 1,
  }));
  const parsed = parseStoreProduct(
    {
      ...product,
      statusType: "OUTOFSTOCK",
      options: [{ groupName: "색" }],
      optionCombinations: many,
    },
    "u",
  )!;
  expect(parsed.naver_pay_buy).toBe(false);
  expect(parsed.options[0]!.values).toHaveLength(100);
  expect(parsed.options_truncated).toBe(true);
});

test("상품이 없으면 null, 이름이나 가격이나 배송 칸이 없으면 빠진 칸을 알린다", () => {
  expect(parseStoreProduct(null, "u")).toBeNull();
  expect(() => parseStoreProduct({ name: "x" }, "u")).toThrow(MissingFields);
  try {
    parseStoreProduct({ name: "x" }, "u");
  } catch (error) {
    expect((error as MissingFields).fields).toEqual(["price", "delivery"]);
  }
});

test("가격비교 묶음은 네이버페이로 바로 사는 스토어 판매처만 싣는다", () => {
  const parsed = parseCatalog(
    {
      info: { productName: "물티슈 묶음" },
      products: [
        {
          mallName: "가게 A",
          price: 9900,
          deliveryFee: 0,
          pcProductUrl: "https://smartstore.naver.com/a/products/1",
          isNaverPay: true,
        },
        {
          mallName: "가게 B",
          price: 9800,
          pcProductUrl: "https://www.example-mall.com/p/2",
          isNaverPay: true,
        },
        {
          mallName: "가게 C",
          price: 9700,
          pcProductUrl: "https://smartstore.naver.com/c/products/3",
          npayYn: "N",
        },
      ],
    },
    "https://search.shopping.naver.com/catalog/1",
  );
  expect(parsed.title).toBe("물티슈 묶음");
  expect(parsed.sellers).toEqual([
    {
      store: "가게 A",
      price_krw: 9900,
      shipping_fee_krw: 0,
      url: "https://smartstore.naver.com/a/products/1",
    },
  ]);
});

test("묶음 화면에 이름이나 판매처 목록이 없으면 빠진 칸을 알린다", () => {
  expect(() => parseCatalog({ info: {} }, "u")).toThrow(MissingFields);
});
