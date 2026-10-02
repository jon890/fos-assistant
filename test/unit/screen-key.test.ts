import assert from "node:assert/strict";
import { test } from "node:test";
import { screenKey, viewTransitionEnabled } from "../../web/src/components/shell/screen-key.ts";

test("대화 화면은 주소가 달라도 같은 화면으로 본다", () => {
  assert.equal(screenKey("/"), "chat");
  assert.equal(screenKey("/chat/abc"), "chat");
});

test("대화가 아닌 화면은 경로가 그대로 화면의 단위다", () => {
  assert.equal(screenKey("/usage"), "/usage");
  // `/chat` 으로 시작하기만 하는 다른 경로는 대화 화면이 아니다.
  assert.equal(screenKey("/chatter"), "/chatter");
});

test("화면 전환은 `off` 를 줄 때만 꺼진다", () => {
  assert.equal(viewTransitionEnabled(undefined), true);
  assert.equal(viewTransitionEnabled("off"), false);
  assert.equal(viewTransitionEnabled("on"), true);
  // 이미지를 빌드할 때 값을 주지 않으면 빈 문자열이 온다.
  assert.equal(viewTransitionEnabled(""), true);
});
