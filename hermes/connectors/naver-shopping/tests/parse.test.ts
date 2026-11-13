import { expect, test } from "bun:test";
import {
  MissingFields,
  parseCatalog,
  parseStoreProduct,
} from "../src/parse-product.ts";
import { parseSearch, searchLooksChanged } from "../src/parse-search.ts";

/** 합성 검색 항목(고정 식이 주는 평평한 모양). 실제 네이버 화면을 옮긴 것이 아니다. */
function item(index: number, extra: Record<string, unknown> = {}) {
  return {
    productName: `상품 ${index}`,
    salePrice: 2000 + index,
    discountedSalePrice: 1000 + index,
    mallName: `가게 ${index}`,
    productUrl: {
      pcUrl: `https://smartstore.naver.com/shop${index}/products/${index}`,
    },
    nvMid: `mid${index}`,
    ...extra,
  };
}

test("검색 결과는 40개까지, 화면 차례 그대로, 광고와 네이버페이 표시를 싣는다", () => {
  const list = [
    item(0, { cardType: "AD", adId: "ad0" }),
    item(1, { cardType: "SUPER_POINT" }),
    item(2, { cardType: "AD" }),
    item(3, { adId: "ad3" }),
    ...Array.from({ length: 43 }, (_, i) => item(i + 4)),
  ];
  const products = parseSearch(list)!;
  expect(products).toHaveLength(40);
  expect(products[0]).toMatchObject({
    rank: 1,
    title: "상품 0",
    price_krw: 1000,
    store: "가게 0",
    url: "https://smartstore.naver.com/shop0/products/0",
    ad: true,
    naver_pay: "unknown",
    kind: "store",
  });
  expect(products.slice(1, 4).map((product) => product.ad)).toEqual([
    false,
    true,
    true,
  ]);
  expect(products.map((product) => product.rank)).toEqual(
    Array.from({ length: 40 }, (_, i) => i + 1),
  );
});

test("가격은 할인가가 있으면 할인가, 없거나 0 이면 정가다", () => {
  const products = parseSearch([
    item(1),
    item(2, { discountedSalePrice: undefined }),
    item(3, { discountedSalePrice: 0 }),
    item(4, { discountedSalePrice: undefined, salePrice: undefined }),
  ])!;
  expect(products.map((product) => product.price_krw)).toEqual([
    1001, 2002, 2003,
  ]);
});

test("같은 nvMid 는 화면에서 처음 나온 한 번만 내고, nvMid 가 없는 항목은 모두 낸다", () => {
  const products = parseSearch([
    item(1, { cardType: "AD", adId: "ad1" }),
    item(2),
    item(1, { productName: "겹친 상품" }),
    item(3, { nvMid: undefined }),
    item(4, { nvMid: undefined }),
  ])!;
  expect(products.map((product) => [product.rank, product.title])).toEqual([
    [1, "상품 1"],
    [2, "상품 2"],
    [3, "상품 3"],
    [4, "상품 4"],
  ]);
  expect(products[0]!.ad).toBe(true);
});

test("거절 주소와 칸이 빠진 항목은 빼고, 바깥 쇼핑몰은 external 로 둔다", () => {
  const products = parseSearch([
    item(1, {
      productUrl: { pcUrl: "http://smartstore.naver.com/a/products/1" },
    }),
    item(2, { salePrice: undefined, discountedSalePrice: undefined }),
    item(3, { productUrl: { pcUrl: "https://www.example-mall.com/p/3" } }),
    item(4, { productUrl: undefined }),
    item(5, { productName: undefined }),
  ])!;
  expect(products.map((product) => [product.title, product.kind])).toEqual([
    ["상품 3", "external"],
  ]);
});

test("목록이 배열이 아니면 null, 항목이 있는데 하나도 못 읽으면 바뀐 화면이다", () => {
  expect(parseSearch({ list: [] })).toBeNull();
  expect(parseSearch(null)).toBeNull();
  const unreadable = [{ name: "다른 모양" }];
  expect(searchLooksChanged(unreadable, parseSearch(unreadable)!)).toBe(true);
  expect(searchLooksChanged([], parseSearch([])!)).toBe(false);
});

/** 2026-11-13 실측 모양을 줄인 합성 상품. 옵션 칸이 없고 유료 배송이다. */
const product = {
  name: "물티슈 100매",
  channel: { channelName: "좋은가게" },
  salePrice: 12000,
  benefitsView: { discountedSalePrice: 10900 },
  productStatusType: "SALE",
  optionUsable: false,
  productDeliveryInfo: {
    deliveryFeeType: "PAID",
    baseFee: 3000,
    area2ExtraFee: 3000,
  },
};
const url = "https://smartstore.naver.com/a/products/1";

test("옵션 없는 상품은 할인가, 판매처, 유료 배송비와 지역 추가 배송비를 싣는다", () => {
  const parsed = parseStoreProduct(product, url)!;
  expect(parsed.price_krw).toBe(10900);
  expect(parsed.store).toBe("좋은가게");
  expect(parsed.options).toEqual([]);
  expect(parsed.shipping).toEqual({
    fee_krw: 3000,
    free_over_krw: null,
    regional_extra: true,
  });
  expect(parsed.naver_pay_buy).toBe(true);
  expect("options_truncated" in parsed).toBe(false);
});

test("무료 배송이면 배송비 0 이고, 지역 추가 배송비 칸이 없거나 0 이면 없다고 본다", () => {
  const free = parseStoreProduct(
    {
      ...product,
      productDeliveryInfo: { deliveryFeeType: "FREE", baseFee: 0 },
    },
    url,
  )!;
  expect(free.shipping).toEqual({
    fee_krw: 0,
    free_over_krw: null,
    regional_extra: false,
  });
  const zero = parseStoreProduct(
    {
      ...product,
      productDeliveryInfo: {
        deliveryFeeType: "PAID",
        baseFee: 2500,
        area2ExtraFee: 0,
      },
    },
    url,
  )!;
  expect(zero.shipping.fee_krw).toBe(2500);
  expect(zero.shipping.regional_extra).toBe(false);
  // 앞 후보가 0 이어도 뒤 후보가 0 보다 크면 추가 배송비가 있다.
  const later = parseStoreProduct(
    {
      ...product,
      productDeliveryInfo: {
        deliveryFeeType: "PAID",
        baseFee: 2500,
        area2ExtraFee: 0,
        area3ExtraFee: 5000,
      },
    },
    url,
  )!;
  expect(later.shipping.regional_extra).toBe(true);
});

test("optionUsable 이 false 면 옵션 칸이 있어도 옵션 없음이고, true 인데 칸이 없으면 지어내지 않는다", () => {
  const stray = parseStoreProduct(
    { ...product, options: [{ groupName: "수량" }] },
    url,
  )!;
  expect(stray.options).toEqual([]);
  expect("options_unknown" in stray).toBe(false);
  const unknown = parseStoreProduct({ ...product, optionUsable: true }, url)!;
  expect(unknown.options).toEqual([]);
  expect(unknown.options_unknown).toBe(true);
});

test("옵션 묶음은 읽었지만 값이 하나도 없으면 options_unknown 을 싣는다", () => {
  const parsed = parseStoreProduct(
    { ...product, optionUsable: true, options: [{ groupName: "수량" }] },
    url,
  )!;
  expect(parsed.options_unknown).toBe(true);
});

test("조합형 옵션은 첫 묶음 값의 추가 금액과 품절을 싣는다(옵션 칸 이름은 가설)", () => {
  const parsed = parseStoreProduct(
    {
      ...product,
      optionUsable: true,
      options: [{ groupName: "수량" }, { groupName: "향" }],
      optionCombinations: [
        {
          optionName1: "10팩",
          optionName2: "무향",
          price: 0,
          stockQuantity: 3,
        },
        {
          optionName1: "10팩",
          optionName2: "레몬",
          price: 500,
          stockQuantity: 0,
        },
        {
          optionName1: "20팩",
          optionName2: "무향",
          price: 9000,
          stockQuantity: 0,
        },
      ],
    },
    url,
  )!;
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
      productStatusType: "OUTOFSTOCK",
      optionUsable: true,
      options: [{ groupName: "색" }],
      optionCombinations: many,
    },
    "u",
  )!;
  expect(parsed.naver_pay_buy).toBe(false);
  expect(parsed.options[0]!.values).toHaveLength(100);
  expect(parsed.options_truncated).toBe(true);
  expect("options_unknown" in parsed).toBe(false);
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

test("가격비교 묶음은 이름, 최저가, 배송비를 읽고 판매처 목록은 읽지 못했다고 싣는다", () => {
  const parsed = parseCatalog(
    {
      nCatalogId: "1000",
      productName: "합성 묶음",
      lowestPrice: 9900,
      deliveryFee: 2500,
      lowestPriceWithFee: 12400,
      differentialDeliveryFeeContent: "제주 추가 3,000원",
      categoryId: "50000001",
    },
    "https://search.shopping.naver.com/catalog/1000",
  );
  expect(parsed).toEqual({
    kind: "catalog",
    url: "https://search.shopping.naver.com/catalog/1000",
    title: "합성 묶음",
    lowest_price_krw: 9900,
    delivery_fee_krw: 2500,
    lowest_price_with_fee_krw: 12400,
    regional_extra: true,
    sellers: [],
    offers_unknown: true,
  });
});

test("묶음의 배송비 칸이 비면 지어내지 않고 null 이고, 지역 추가 배송비 안내가 없으면 거짓이다", () => {
  expect(
    parseCatalog({ productName: "합성 묶음", lowestPrice: 9900 }, "u"),
  ).toMatchObject({
    lowest_price_krw: 9900,
    delivery_fee_krw: null,
    lowest_price_with_fee_krw: null,
    regional_extra: false,
  });
  // 무료 배송은 0 이다.
  expect(
    parseCatalog(
      { productName: "합성 묶음", lowestPrice: 9900, deliveryFee: 0 },
      "u",
    ),
  ).toMatchObject({ delivery_fee_krw: 0 });
});

test("묶음 화면에 이름이나 최저가가 없으면 빠진 칸을 알린다", () => {
  expect(() => parseCatalog({ lowestPrice: 1 }, "u")).toThrow(MissingFields);
  try {
    parseCatalog({}, "u");
  } catch (error) {
    expect((error as MissingFields).fields).toEqual(["title", "lowest_price"]);
  }
  expect(() => parseCatalog(null, "u")).toThrow(MissingFields);
});
