import assert from "node:assert/strict";
import test from "node:test";
import {
  compareDecimal,
  decimalExceeds,
  multiplyDecimal,
  parseDecimal,
} from "../../web/src/lib/decimal.ts";

test("수량과 가격의 곱이 상한과 딱 같으면 넘지 않는다", () => {
  // 부동소수점으로는 3 * 1.1 = 3.3000000000000003 이라 상한 3.3 을 넘는 것으로 잘못 보인다.
  const value = multiplyDecimal(parseDecimal("3")!, parseDecimal("1.1")!);
  assert.equal(compareDecimal(value, parseDecimal("3.3")!), 0);
  assert.equal(compareDecimal(value, parseDecimal("3.29")!), 1);
  assert.equal(compareDecimal(value, parseDecimal("3.300001")!), -1);
});

test("십진 글은 자릿수가 달라도 같은 값으로 견주고 읽지 못하면 넘지 않는 것으로 본다", () => {
  assert.equal(decimalExceeds("1000.000001", "1000"), true);
  assert.equal(decimalExceeds("1000.0", "1000"), false);
  assert.equal(decimalExceeds("1e3", "1"), false);
  assert.equal(parseDecimal("-1"), null);
});
