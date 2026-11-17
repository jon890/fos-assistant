import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { createHeadingIds, headingText } from "../../src/lib/dev-docs/headings.ts";

test("한글 제목은 GitHub 와 같은 id 가 되고 같은 제목은 -1 이 붙으며 새 문서는 다시 시작한다", () => {
  const ids = createHeadingIds();
  assert.equal(ids("사람이 도와야 하는 지점"), "사람이-도와야-하는-지점");
  assert.equal(ids("사람이 도와야 하는 지점"), "사람이-도와야-하는-지점-1");
  assert.equal(ids("「고급」 묶음과 `code`"), "고급-묶음과-code");
  assert.equal(createHeadingIds()("사람이 도와야 하는 지점"), "사람이-도와야-하는-지점");
});

test("제목 글자는 안쪽 요소의 글자까지 모은다", () => {
  assert.equal(headingText(["앞 ", createElement("code", null, "값"), " 뒤"]), "앞 값 뒤");
  assert.equal(headingText(null), "");
});
