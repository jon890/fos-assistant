import assert from "node:assert/strict";
import { test } from "node:test";
import { artifactName, artifactNames } from "../../web/src/lib/artifact-name.ts";

test("index.html 이면 폴더 이름이 이름이다", () => {
  assert.equal(artifactName("제주-여행/index.html"), "제주-여행");
});

test("index.html 이 아니면 파일 이름이 이름이다", () => {
  assert.equal(artifactName("초안/본문.html"), "본문.html");
});

test("경로가 index.html 하나면 그대로 index.html 이다", () => {
  assert.equal(artifactName("index.html"), "index.html");
});

test("폴더 이름이 같으면 마지막 두 조각으로 나눈다", () => {
  const names = artifactNames(["가/초안/index.html", "나/초안/index.html"]);
  assert.equal(names.get("가/초안/index.html"), "가/초안");
  assert.equal(names.get("나/초안/index.html"), "나/초안");
});

test("이름이 서로 다르면 폴더 이름만 쓴다", () => {
  const names = artifactNames(["a/index.html", "b/index.html"]);
  assert.equal(names.get("a/index.html"), "a");
  assert.equal(names.get("b/index.html"), "b");
});
