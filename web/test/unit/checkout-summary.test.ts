import assert from "node:assert/strict";
import { test } from "node:test";
import { safeCheckout } from "../../src/lib/checkout-summary-route.ts";

/** Control Plane `CheckoutActionSummary` 의 모양이다. 합성 값이다. */
const row = {
  available: true,
  title: "합성 상품",
  store: "합성몰",
  options: [{ name: "색", value: "검정" }],
  quantity: 2,
  itemAmountKrw: 22000,
  shippingFeeKrw: 3000,
  expectedTotalKrw: 25000,
  regionalExtraFee: true,
  url: "https://smartstore.naver.com/shop/products/1",
  remainingTodayKrw: 50000,
};

test("주문서 요약은 계약의 칸만 옮기고 배송지·결제 수단 같은 칸이 더해져도 버린다", () => {
  assert.deepEqual(safeCheckout(row), row);
  const withExtra = safeCheckout({
    ...row,
    address: "합성 주소",
    paymentMethod: "합성 수단",
  }) as Record<string, unknown>;
  assert.equal("address" in withExtra, false);
  assert.equal("paymentMethod" in withExtra, false);
});

test("열지 못한 줄이나 모양이 틀린 줄은 승인할 수 없는 줄(available 거짓)로 낮춘다", () => {
  for (const broken of [
    { available: false },
    { ...row, itemAmountKrw: -1 },
    { ...row, expectedTotalKrw: 1_000_000_000 },
    { ...row, quantity: 0 },
    { ...row, title: "" },
    { ...row, options: [{ name: "색" }] },
    { ...row, regionalExtraFee: "yes" },
    "text",
  ]) {
    const safe = safeCheckout(broken);
    assert.equal(safe.available, false);
    assert.equal(safe.title, null);
    assert.deepEqual(safe.options, []);
  }
});

test("https 가 아닌 상품 링크는 그 칸만 비우고, 남은 한도를 읽지 못했으면 null 이다", () => {
  const safe = safeCheckout({
    ...row,
    url: "javascript:alert(1)",
    remainingTodayKrw: null,
  });
  assert.equal(safe.available, true);
  assert.equal(safe.url, null);
  assert.equal(safe.remainingTodayKrw, null);
});
