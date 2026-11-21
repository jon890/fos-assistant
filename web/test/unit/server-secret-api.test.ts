import assert from "node:assert/strict";
import test from "node:test";
import {
  regenerateConfirmText,
  reinstallSummary,
} from "../../src/lib/server-secret-api.ts";

test("다시 만들기 확인 창은 바인딩 수를 쓰고 수를 모르면 「붙은」 으로 쓴다", () => {
  assert.equal(
    regenerateConfirmText(3),
    "브라우저 플러그인 바인딩 3개를 다시 설치합니다. 자동으로 반영될 때까지 그 플러그인은 동작하지 않습니다.",
  );
  assert.match(regenerateConfirmText(null), /^붙은 브라우저 플러그인 바인딩을 다시 설치합니다/);
});

test("다시 설치 결과는 성공, 일부 실패, 목록 읽기 실패를 나눠 알린다", () => {
  assert.deepEqual(reinstallSummary({ total: 0, restartPending: 0, failed: 0, catalogUnavailable: false }), [
    "새 비밀을 만들었어요.",
  ]);
  assert.deepEqual(reinstallSummary({ total: 3, restartPending: 2, failed: 1, catalogUnavailable: false }), [
    "2개를 다시 설치했어요. 자동으로 반영될 때까지 브라우저 플러그인은 동작하지 않습니다.",
    "1개는 다시 설치하지 못했어요. 바인딩 화면에서 확인하고, 그대로면 비밀을 다시 만들어 주세요.",
  ]);
  assert.match(
    reinstallSummary({ total: 0, restartPending: 0, failed: 0, catalogUnavailable: true })[0]!,
    /바인딩 목록을 읽지 못해/,
  );
});
