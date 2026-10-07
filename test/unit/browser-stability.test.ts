import assert from "node:assert/strict";
import test from "node:test";
import { selectSpecs } from "../../scripts/browser-stability.mjs";

test("새 spec과 고친 spec은 경로가 정확히 맞는 것만 반복한다", () => {
  assert.deepEqual(
    selectSpecs([
      "test/browser/chat.spec.ts",
      "test/browser/new.spec.ts",
      "web/src/new.spec.ts",
    ]),
    ["chat.spec.ts", "new.spec.ts"],
  );
  assert.deepEqual(selectSpecs(["web/src/app/page.tsx"]), []);
});

test("공통 대역 변경과 매일 실행은 공유 상태 회귀 묶음을 반복한다", () => {
  const regression = [
    "fixture-isolation.spec.ts",
    "now.spec.ts",
    "persona.spec.ts",
    "usage.spec.ts",
  ];
  assert.deepEqual(selectSpecs(["test/e2e/fake-hermes.ts"]), regression);
  assert.deepEqual(selectSpecs([], true), regression);
  assert.deepEqual(
    selectSpecs(["test/browser/now.spec.ts", "test/browser/fixtures.ts"]),
    regression,
  );
});
