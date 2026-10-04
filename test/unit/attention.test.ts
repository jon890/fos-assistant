import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import {
  allCardsEmpty,
  itemHref,
  moreText,
  nowLinkLabel,
  originText,
  reasonText,
  type AttentionCard,
  type AttentionCardKey,
  type AttentionItem,
  type AttentionWhy,
} from "../../web/src/lib/attention.ts";

const NOW_DOC = join(import.meta.dirname, "../../docs/frontend/now.md");

function why(trigger: string, signals: string[]): AttentionWhy {
  return { trigger, signals, confidence: "CONTROL_PLANE", sources: [] };
}

function item(overrides: Partial<AttentionItem>): AttentionItem {
  return {
    itemKey: "conversation:demo",
    stateKey: "state-demo",
    attention: "NOW",
    channel: "IN_APP",
    title: "주간 장보기 목록 정리",
    conversationId: null,
    agentName: "장보기 비서",
    at: "2026-10-05T11:00:00Z",
    why: why("CONVERSATION_RECENT", []),
    execution: null,
    actionId: null,
    followUp: null,
    ...overrides,
  };
}

function card(key: AttentionCardKey, overrides: Partial<AttentionCard> = {}): AttentionCard {
  return { key, status: "OK", nowCount: 0, moreCount: 0, items: [], ...overrides };
}

/** `docs/frontend/now.md` 「이유 문구」 표의 줄을 읽는다. 문서와 코드의 문구가 같은지 이 표로 본다. */
async function reasonRows(): Promise<{ trigger: string; signals: string[]; text: string }[]> {
  const doc = await readFile(NOW_DOC, "utf-8");
  const section = doc.split("## 이유 문구")[1]?.split("\n## ")[0] ?? "";
  const rows = section
    .split("\n")
    .filter((line) => /^\| `[A-Z_]+` \|/.test(line))
    .map((line) => line.split("|").slice(1, -1).map((cell) => cell.trim()))
    .map(([trigger, signal, text]) => ({
      trigger: trigger.replaceAll("`", ""),
      signals: signal === "없음" ? [] : [signal.replace(/\s*만$/, "").replaceAll("`", "")],
      text,
    }));
  assert.ok(rows.length > 0, "now.md 에서 「이유 문구」 표를 찾지 못했다");
  return rows;
}

test("이유 문구는 now.md 「이유 문구」 표의 줄마다 그 문구다", async () => {
  for (const row of await reasonRows()) {
    assert.equal(
      reasonText(why(row.trigger, row.signals)),
      row.text,
      `${row.trigger} 에 ${JSON.stringify(row.signals)} 의 문구가 표와 다르다`,
    );
  }
});

test("신호가 여럿이면 표의 위쪽 줄을 쓴다", () => {
  assert.equal(reasonText(why("FOLLOW_UP_OPEN", ["OVERDUE", "WAITING"])), "기한이 지났어요");
});

test("표에 없는 조합은 그 trigger 의 「없음」 줄을 쓴다", () => {
  assert.equal(reasonText(why("FOLLOW_UP_OPEN", ["LONG_RUNNING"])), "챙기고 있는 할 일이에요");
});

test("모르는 trigger 는 빈 글이다", () => {
  assert.equal(reasonText(why("SOMETHING_NEW", [])), "");
});

test("네 카드가 모두 읽혔고 비었으면 모두 빈 것이다", () => {
  const cards = (["failures", "needs_me", "delegated", "continue"] as const).map((key) => card(key));
  assert.equal(allCardsEmpty(cards), true);
});

test("읽지 못한 카드가 하나라도 있으면 모두 빈 것이 아니다", () => {
  const cards = [card("failures"), card("needs_me"), card("delegated"), card("continue", { status: "UNAVAILABLE" })];
  assert.equal(allCardsEmpty(cards), false);
});

test("맡긴 일의 제목은 작업 과정으로 간다", () => {
  const delegated = item({ why: why("DELEGATION_RUNNING", []), execution: { id: 41, status: "RUNNING" } });
  assert.equal(itemHref(delegated), "/executions/41");
});

test("대화가 없는 할 일의 제목은 갈 곳이 없다", () => {
  assert.equal(itemHref(item({ why: why("FOLLOW_UP_OPEN", []), conversationId: null })), null);
});

test("대화가 있는 실패의 제목은 그 대화로 가고, 기억 제안은 기억 화면으로 간다", () => {
  const conversationId = "7b1e0000-0000-4000-8000-000000000000";
  assert.equal(itemHref(item({ why: why("EXECUTION_FAILED", ["NOT_RETRIED"]), conversationId })), `/chat/${conversationId}`);
  assert.equal(itemHref(item({ why: why("MEMORY_PROPOSED", []) })), "/memory");
});

test("할 일의 출처는 제안이면 「대화에서」, 사람이 더했으면 「직접 더함」, 할 일이 아니면 없다", () => {
  const followUp = { id: "f-1", dueAt: null, waiting: false };
  assert.equal(originText(item({ followUp: { ...followUp, proposed: true } })), "대화에서");
  assert.equal(originText(item({ followUp: { ...followUp, proposed: false } })), "직접 더함");
  assert.equal(originText(item({ followUp: null })), null);
});

test("더 있는 항목은 실패면 실행 기록으로, 내 차례면 글만 낸다", () => {
  assert.deepEqual(moreText(card("failures", { moreCount: 2 })), { text: "2개 더 있어요", href: "/usage?tab=executions" });
  assert.deepEqual(moreText(card("needs_me", { moreCount: 1 })), { text: "1개 더 있어요", href: null });
});

test("더 있는 항목이 없으면 안내가 없다", () => {
  assert.equal(moreText(card("delegated")), null);
});

test("「지금 볼 것」 링크의 이름은 0 이면 수를 싣지 않는다", () => {
  assert.equal(nowLinkLabel(0), "지금 볼 것");
  assert.equal(nowLinkLabel(3), "지금 볼 것 3건");
});
