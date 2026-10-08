import assert from "node:assert/strict";
import { test } from "node:test";
import {
  dragWheel,
  isTap,
  MAX_TEXT,
  mouse,
  ratio,
  resizeFor,
  screenKey,
  textInputs,
  webUrl,
  wheel,
  wheelDelta,
} from "../../web/src/components/browser/screen-input.ts";

const box = { left: 100, top: 50, width: 400, height: 600 };

test("좌표는 그림 안의 비율이고 가장자리와 밖은 0 과 1 로 자른다", () => {
  assert.deepEqual(ratio({ clientX: 300, clientY: 350 }, box), {
    x: 0.5,
    y: 0.5,
  });
  assert.deepEqual(ratio({ clientX: 100, clientY: 50 }, box), { x: 0, y: 0 });
  assert.deepEqual(ratio({ clientX: 500, clientY: 650 }, box), { x: 1, y: 1 });
  assert.deepEqual(ratio({ clientX: 20, clientY: 900 }, box), { x: 0, y: 1 });
  assert.deepEqual(
    ratio({ clientX: 10, clientY: 10 }, { ...box, width: 0, height: 0 }),
    { x: 0, y: 0 },
  );
});

test("마우스 입력은 동작과 비율 좌표를 싣는다", () => {
  assert.deepEqual(mouse("down", { clientX: 200, clientY: 200 }, box), {
    type: "mouse",
    action: "down",
    x: 0.25,
    y: 0.25,
  });
});

test("위로 끌면 아래로 굴리고 끈 거리를 프레임 픽셀로 늘린다", () => {
  const start = { clientX: 300, clientY: 350 };
  // 그림 높이 600 에 프레임 높이 1200 이라 두 배다.
  assert.deepEqual(dragWheel(start, 400, 300, box, 1200), {
    type: "wheel",
    x: 0.5,
    y: 0.5,
    deltaY: 200,
  });
  assert.deepEqual(dragWheel(start, 300, 350, box, 1200), {
    type: "wheel",
    x: 0.5,
    y: 0.5,
    deltaY: -100,
  });
  // 프레임 높이를 모르면 그대로다.
  assert.deepEqual(dragWheel(start, 300, 280, box, 0), {
    type: "wheel",
    x: 0.5,
    y: 0.5,
    deltaY: 20,
  });
});

test("휠 값은 2000 으로 자르고 정수로 보낸다", () => {
  assert.equal(
    (wheel({ clientX: 300, clientY: 350 }, box, 5000) as { deltaY: number })
      .deltaY,
    2000,
  );
  assert.equal(
    (wheel({ clientX: 300, clientY: 350 }, box, -5000) as { deltaY: number })
      .deltaY,
    -2000,
  );
  assert.equal(
    (wheel({ clientX: 300, clientY: 350 }, box, 12.6) as { deltaY: number })
      .deltaY,
    13,
  );
});

test("움직임 없이 뗀 짧은 터치는 누르기다", () => {
  const start = { clientX: 100, clientY: 100 };
  assert.equal(isTap(start, { clientX: 104, clientY: 106 }), true);
  assert.equal(isTap(start, { clientX: 100, clientY: 130 }), false);
});

test("특수 키는 key 로 보내고 모르는 키는 보내지 않는다", () => {
  for (const key of [
    "Enter",
    "Backspace",
    "Tab",
    "Escape",
    "ArrowUp",
    "ArrowDown",
    "ArrowLeft",
    "ArrowRight",
    "Delete",
  ]) {
    assert.deepEqual(screenKey(key), { type: "key", key });
  }
  for (const key of [
    "a",
    "가",
    "Shift",
    "Process",
    "Unidentified",
    "F5",
    "Home",
  ]) {
    assert.equal(screenKey(key), null);
  }
});

test("글자는 상한 길이로 나눠 보내고 서로게이트 쌍을 쪼개지 않는다", () => {
  assert.deepEqual(textInputs(""), []);
  assert.deepEqual(textInputs("안녕"), [{ type: "text", text: "안녕" }]);
  const long = "가".repeat(MAX_TEXT + 3);
  assert.deepEqual(
    textInputs(long).map((input) => (input as { text: string }).text.length),
    [MAX_TEXT, 3],
  );
  const emoji = "a" + "😀".repeat(MAX_TEXT / 2);
  assert.deepEqual(
    textInputs(emoji).map((input) => (input as { text: string }).text.length),
    [MAX_TEXT - 1, 2],
  );
});

test("크기는 폭의 1.5배 높이이고 계약의 범위로 자른다", () => {
  assert.deepEqual(resizeFor(390), { type: "resize", width: 390, height: 585 });
  assert.deepEqual(resizeFor(200), { type: "resize", width: 320, height: 480 });
  assert.deepEqual(resizeFor(1500), {
    type: "resize",
    width: 1500,
    height: 2000,
  });
  assert.deepEqual(resizeFor(3000), {
    type: "resize",
    width: 1600,
    height: 2000,
  });
});

test("주소는 host 가 있는 http 와 https 만 받고 정규화해 돌려준다", () => {
  assert.equal(
    webUrl("https://example.com/login"),
    "https://example.com/login",
  );
  assert.equal(webUrl("http://EXAMPLE.com"), "http://example.com/");
  assert.equal(webUrl("https://example.com/a b"), "https://example.com/a%20b");
  assert.equal(webUrl("javascript:alert(1)"), null);
  assert.equal(webUrl("file:///etc/hosts"), null);
  assert.equal(webUrl("example.com"), null);
  assert.equal(webUrl(""), null);
  assert.equal(webUrl(null), null);
  assert.equal(webUrl(`https://example.com/${"a".repeat(2048)}`), null);
});

test("휠 값은 픽셀이면 그대로, 줄이면 16배, 쪽이면 그림 높이배다", () => {
  assert.equal(wheelDelta(30, 0, 600), 30);
  assert.equal(wheelDelta(3, 1, 600), 48);
  assert.equal(wheelDelta(-1, 2, 600), -600);
});
