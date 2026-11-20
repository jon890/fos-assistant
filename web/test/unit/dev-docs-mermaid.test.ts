import assert from "node:assert/strict";
import test from "node:test";
import { mermaidRenderId } from "../../src/lib/mermaid.ts";

test("mermaid 요소 id 는 영숫자만 남기고 입력마다 다르다", () => {
  assert.match(mermaidRenderId("«r1»"), /^mmd-[A-Za-z0-9]+$/);
  assert.match(mermaidRenderId(":r2:"), /^mmd-[A-Za-z0-9]+$/);
  assert.notEqual(mermaidRenderId(":r1:"), mermaidRenderId(":r2:"));
  assert.equal(mermaidRenderId(":::"), "mmd-0");
});
