import assert from "node:assert/strict";
import { test } from "node:test";
import { holdBand } from "../../src/lib/screen-hold.ts";

const none = { human: false, approved: false };

test("사람이 쥐었으면 어느 탭을 보든 직접 하는 중 띠다", () => {
  assert.equal(holdBand({ human: true, approved: false }, false), "human");
  assert.equal(holdBand({ human: true, approved: true }, true), "human");
});

test("에이전트가 쥔 탭을 볼 때만 띠가 있고, 승인한 실행 중이면 마무리 띠다", () => {
  assert.equal(holdBand(none, true), "agent");
  assert.equal(holdBand({ human: false, approved: true }, true), "approved");
  assert.equal(holdBand(none, false), null);
  assert.equal(holdBand({ human: false, approved: true }, false), null);
});
