import assert from "node:assert/strict";
import test from "node:test";
import { PushSubscribeTimeoutError, withTimeout } from "../../src/lib/with-timeout.ts";

test("끝나지 않는 구독은 제한 시간 뒤 시간 초과로 거절된다", async () => {
  await assert.rejects(
    withTimeout(new Promise<never>(() => undefined), 20),
    PushSubscribeTimeoutError,
  );
});

test("제한 시간 안에 끝나면 결과를 그대로 준다", async () => {
  assert.equal(await withTimeout(Promise.resolve(7), 1000), 7);
});
