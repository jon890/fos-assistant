import assert from "node:assert/strict";
import test from "node:test";
import {
  mergeUsageExecutionPage,
  type UsageExecutionPage,
} from "../../web/src/lib/usage-paging.ts";

function page(ids: number[], nextCursor: string | null): UsageExecutionPage {
  return {
    items: ids.map((id) => ({ id }) as UsageExecutionPage["items"][number]),
    nextCursor,
  };
}

test("다음 쪽은 읽은 순서를 유지하며 이어 붙인다", () => {
  const merged = mergeUsageExecutionPage(page([5, 4], "after-4"), page([3, 2], null));

  assert.deepEqual(merged.items.map((item) => item.id), [5, 4, 3, 2]);
  assert.equal(merged.nextCursor, null);
});

test("겹쳐 오거나 다음 쪽 안에서 반복된 실행 번호는 한 번만 보인다", () => {
  const merged = mergeUsageExecutionPage(
    page([5, 4], "after-4"),
    page([4, 3, 3], "after-3"),
  );

  assert.deepEqual(merged.items.map((item) => item.id), [5, 4, 3]);
  assert.equal(merged.nextCursor, "after-3");
});

test("빈 마지막 쪽은 기존 기록을 유지하고 다음 커서를 비운다", () => {
  const merged = mergeUsageExecutionPage(page([5], "after-5"), page([], null));

  assert.deepEqual(merged.items.map((item) => item.id), [5]);
  assert.equal(merged.nextCursor, null);
});
