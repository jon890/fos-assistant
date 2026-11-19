import assert from "node:assert/strict";
import { test } from "node:test";
import { putFileKind, putFileName } from "../../src/lib/put-file.ts";

test("확장자로 그림, 문서, 표를 고른다", () => {
  assert.equal(putFileKind("reports/가계부.xlsx"), "표");
  assert.equal(putFileKind("a.CSV"), "표");
  assert.equal(putFileKind("보고서.pdf"), "문서");
  assert.equal(putFileKind("memo.md"), "문서");
  assert.equal(putFileKind("generated/고양이.png"), "그림");
  assert.equal(putFileKind("archive"), "파일");
});

test("제목이 있으면 제목, 없으면 파일 이름이다", () => {
  assert.equal(
    putFileName({ path: "reports/가계부.xlsx", title: "11월 가계부" }),
    "11월 가계부",
  );
  assert.equal(putFileName({ path: "reports/가계부.xlsx", title: null }), "가계부.xlsx");
  assert.equal(putFileName({ path: "reports/가계부.xlsx", title: "  " }), "가계부.xlsx");
  assert.equal(putFileName({ path: "제주/index.html" }), "제주");
});
