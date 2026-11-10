import assert from "node:assert/strict";
import test from "node:test";
import { subagentGapDetail, subagentGapTotal } from "../../web/src/lib/subagent-gap.ts";

test("셋 다 0 이면 합계는 0 이고 문구는 비어 있다", () => {
  const gap = { pending: 0, unconfirmed: 0, unpriced: 0 };
  assert.equal(subagentGapTotal(gap), 0);
  assert.equal(subagentGapDetail(gap), "");
});

test("0 이 아닌 하나만 문구에 든다", () => {
  assert.equal(subagentGapDetail({ pending: 0, unconfirmed: 1, unpriced: 0 }), "확인 실패 1건");
  assert.equal(subagentGapTotal({ pending: 0, unconfirmed: 1, unpriced: 0 }), 1);
});

test("셋 다 있으면 순서대로 쉼표로 이어 붙이고 합계를 낸다", () => {
  const gap = { pending: 2, unconfirmed: 1, unpriced: 3 };
  assert.equal(subagentGapDetail(gap), "확인 중 2건, 확인 실패 1건, 가격 미확인 3건");
  assert.equal(subagentGapTotal(gap), 6);
});
