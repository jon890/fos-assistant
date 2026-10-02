import assert from "node:assert/strict";
import test from "node:test";
import { parseBundle } from "../../web/src/lib/memory-import.ts";

function item(overrides: Record<string, unknown> = {}) {
  return {
    sourceRef: "private/wiki/sample/note-a.md",
    sourceDate: "2026-01-02",
    collection: "core",
    entryType: "MEMORY",
    documentKey: null,
    title: "샘플 기록",
    content: "평문-표식-7391",
    sensitive: false,
    retrieval: "SEARCH",
    ...overrides,
  };
}

test("JSON 이 아니면 NOT_JSON 이다", () => {
  assert.deepEqual(parseBundle("{"), { ok: false, reason: "NOT_JSON" });
});

test("schemaVersion 이 1 이 아니면 WRONG_SHAPE 이다", () => {
  assert.deepEqual(parseBundle(JSON.stringify({ schemaVersion: 2, items: [item()] })), {
    ok: false,
    reason: "WRONG_SHAPE",
  });
});

test("항목이 빈 배열이면 EMPTY 다", () => {
  assert.deepEqual(parseBundle(JSON.stringify({ schemaVersion: 1, items: [] })), {
    ok: false,
    reason: "EMPTY",
  });
});

test("항목이 101개면 TOO_MANY 다", () => {
  const items = Array.from({ length: 101 }, (_, index) => item({ sourceRef: `private/wiki/sample/n${index}.md` }));
  assert.deepEqual(parseBundle(JSON.stringify({ schemaVersion: 1, items })), {
    ok: false,
    reason: "TOO_MANY",
  });
});

test("content 가 없는 항목은 WRONG_SHAPE 이다", () => {
  assert.deepEqual(parseBundle(JSON.stringify({ schemaVersion: 1, items: [item({ content: undefined })] })), {
    ok: false,
    reason: "WRONG_SHAPE",
  });
});

test("맞는 묶음에 createdAt 과 모르는 칸이 있으면 버리고 통과한다", () => {
  const parsed = parseBundle(
    JSON.stringify({ schemaVersion: 1, createdAt: "2026-10-02T00:00:00Z", items: [item({ unknown: "x" })] }),
  );
  assert.equal(parsed.ok, true);
  if (parsed.ok) {
    assert.deepEqual(Object.keys(parsed.bundle), ["schemaVersion", "items"]);
    assert.equal("unknown" in parsed.bundle.items[0], false);
    assert.equal(parsed.bundle.items[0].content, "평문-표식-7391");
  }
});
