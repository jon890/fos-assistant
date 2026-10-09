import assert from "node:assert/strict";
import test from "node:test";
import { submittedImages, submittedText } from "../e2e/fake-hermes/run-input.ts";

test("문자열 입력은 그대로 글이고 이미지는 없다", () => {
  assert.equal(submittedText("안녕"), "안녕");
  assert.deepEqual(submittedImages("안녕"), []);
});

test("목록 입력은 첫 글 파트를 글로, 이미지 파트를 이름표와 함께 꺼낸다", () => {
  const input = [
    {
      role: "user",
      content: [
        { type: "text", text: "본문" },
        { type: "text", text: "1번째 사진" },
        { type: "image_url", image_url: { url: "data:image/jpeg;base64,AAAA" } },
      ],
    },
  ];

  assert.equal(submittedText(input), "본문");
  assert.deepEqual(submittedImages(input), [{ label: "1번째 사진", url: "data:image/jpeg;base64,AAAA" }]);
});

test("바로 앞 파트가 글이 아닌 이미지는 이름표가 빈 글이다", () => {
  const input = [{ role: "user", content: [{ type: "image_url", image_url: { url: "data:image/png;base64,BBBB" } }] }];

  assert.deepEqual(submittedImages(input), [{ label: "", url: "data:image/png;base64,BBBB" }]);
});

test("목록 입력은 마지막 항목만 읽는다", () => {
  const input = [
    { role: "user", content: [{ type: "text", text: "앞 글" }, { type: "image_url", image_url: { url: "data:앞" } }] },
    { role: "user", content: "뒤 글" },
  ];

  assert.equal(submittedText(input), "뒤 글");
  assert.deepEqual(submittedImages(input), []);
});

test("입력이 없거나 빈 목록이면 빈 글이다", () => {
  assert.equal(submittedText(undefined), "");
  assert.equal(submittedText([]), "");
  assert.deepEqual(submittedImages(undefined), []);
  assert.deepEqual(submittedImages([]), []);
});
