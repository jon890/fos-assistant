import assert from "node:assert/strict";
import test from "node:test";
import { coalesce } from "../../web/src/lib/coalesce.ts";
import { mergeFirstPage, type ConversationPage } from "../../web/src/components/shell/conversation-page.ts";
import type { Conversation } from "../../web/src/components/shell/conversations-provider.tsx";

function conversation(id: string, updatedAt: string): Conversation {
  return {
    id, title: id, agentCode: "a", agentName: "A", updatedAt,
    provider: null, model: null, reasoningEffort: null,
  };
}

function ids(page: ConversationPage): string[] {
  return page.items.map((item) => item.id);
}

test("읽는 중에 여러 번 불려도 읽기는 진행 중 하나와 뒤따르는 하나뿐이다", async () => {
  let calls = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const refresh = coalesce(async () => {
    calls += 1;
    if (calls === 1) await gate;
  });

  const all = [refresh(), refresh(), refresh(), refresh()];
  assert.equal(calls, 1);
  release();
  await Promise.all(all);

  assert.equal(calls, 2);
});

test("읽기가 끝난 뒤에 부르면 새 읽기를 낸다", async () => {
  let calls = 0;
  const refresh = coalesce(async () => {
    calls += 1;
  });

  await refresh();
  await refresh();

  assert.equal(calls, 2);
});

test("읽기가 실패해도 다음 호출은 다시 읽는다", async () => {
  let calls = 0;
  const refresh = coalesce(async () => {
    calls += 1;
    if (calls === 1) throw new Error("실패");
  });

  await assert.rejects(refresh());
  await refresh();

  assert.equal(calls, 2);
});

test("첫 쪽을 다시 읽어도 이어 읽어 둔 줄은 남는다", () => {
  const current: ConversationPage = {
    items: [
      conversation("a", "2026-01-05T00:00:00Z"),
      conversation("b", "2026-01-04T00:00:00Z"),
      conversation("c", "2026-01-03T00:00:00Z"),
      conversation("d", "2026-01-02T00:00:00Z"),
    ],
    nextCursor: "after-d",
  };
  const fresh: ConversationPage = {
    items: [conversation("n", "2026-01-06T00:00:00Z"), conversation("a", "2026-01-05T00:00:00Z"), conversation("b", "2026-01-04T00:00:00Z")],
    nextCursor: "after-b",
  };

  const merged = mergeFirstPage(fresh, current);

  assert.deepEqual(ids(merged), ["n", "a", "b", "c", "d"]);
  assert.equal(merged.nextCursor, "after-d");
});

test("첫 쪽으로 올라온 줄은 이어 읽은 쪽에서 중복되지 않는다", () => {
  const current: ConversationPage = {
    items: [conversation("a", "2026-01-05T00:00:00Z"), conversation("z", "2026-01-01T00:00:00Z")],
    nextCursor: null,
  };
  const fresh: ConversationPage = {
    items: [conversation("z", "2026-01-07T00:00:00Z"), conversation("a", "2026-01-05T00:00:00Z")],
    nextCursor: "after-a",
  };

  const merged = mergeFirstPage(fresh, current);

  assert.deepEqual(ids(merged), ["z", "a"]);
  assert.equal(merged.nextCursor, "after-a");
});

test("서버가 더 없다고 하면 이어 읽어 둔 줄을 지운다", () => {
  const current: ConversationPage = {
    items: [conversation("a", "2026-01-05T00:00:00Z"), conversation("gone", "2026-01-01T00:00:00Z")],
    nextCursor: null,
  };
  const fresh: ConversationPage = { items: [conversation("a", "2026-01-05T00:00:00Z")], nextCursor: null };

  assert.deepEqual(ids(mergeFirstPage(fresh, current)), ["a"]);
});
