import assert from "node:assert/strict";
import test from "node:test";
import { createHeadingIds, rehypeHeadingIds } from "../../src/lib/dev-docs/headings.ts";

test("한글 제목은 GitHub 와 같은 id 가 되고 같은 제목은 -1 이 붙으며 새 문서는 다시 시작한다", () => {
  const ids = createHeadingIds();
  assert.equal(ids("사람이 도와야 하는 지점"), "사람이-도와야-하는-지점");
  assert.equal(ids("사람이 도와야 하는 지점"), "사람이-도와야-하는-지점-1");
  assert.equal(ids("「고급」 묶음과 `code`"), "고급-묶음과-code");
  assert.equal(createHeadingIds()("사람이 도와야 하는 지점"), "사람이-도와야-하는-지점");
});

test("rehype 플러그인은 제목 안 요소의 글자까지 모아 id 를 붙이고 같은 트리를 다시 처리해도 -1 이 늘지 않는다", () => {
  const heading = (text: string) => ({
    type: "element",
    tagName: "h2",
    properties: {} as Record<string, unknown>,
    children: [{ type: "text", value: "앞 " }, { type: "element", tagName: "code", children: [{ type: "text", value: text }] }],
  });
  const tree = { type: "root", children: [heading("값"), heading("값"), { type: "element", tagName: "p", children: [] }] };
  const run = rehypeHeadingIds();
  run(tree);
  run(tree);
  assert.deepEqual(
    tree.children.map((node) => (node as { properties?: { id?: string } }).properties?.id),
    ["앞-값", "앞-값-1", undefined],
  );
});
