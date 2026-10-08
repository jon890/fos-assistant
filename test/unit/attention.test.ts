import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import {
  allCardsEmpty,
  isoToSeoulInput,
  itemActions,
  itemHref,
  moreText,
  nowLinkLabel,
  originText,
  pruneControls,
  ratioText,
  reasonText,
  seoulInputToIso,
  snoozeUntil,
  visibleNowCount,
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
    report: null,
    problem: null,
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

test("다섯 카드가 모두 읽혔고 비었으면 모두 빈 것이다", () => {
  const cards = (["failures", "needs_me", "delegated", "continue", "reports"] as const).map((key) => card(key));
  assert.equal(allCardsEmpty(cards), true);
});

test("읽지 못한 카드가 하나라도 있으면 모두 빈 것이 아니다", () => {
  const cards = [card("failures"), card("needs_me"), card("delegated"), card("continue"), card("reports", { status: "UNAVAILABLE" })];
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

test("할 일의 출처는 상태와 독립적으로 에이전트 제안이면 「대화에서」다", () => {
  const followUp = { id: "f-1", dueAt: null, waiting: false };
  assert.equal(originText(item({ followUp: { ...followUp, proposed: true, agentProposed: true } })), "대화에서");
  assert.equal(originText(item({ followUp: { ...followUp, proposed: false, agentProposed: true } })), "대화에서");
  assert.equal(originText(item({ followUp: { ...followUp, proposed: false, agentProposed: false } })), "직접 더함");
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

test("카드 머리의 수는 지금 그리는 NOW 항목 가운데 제어한 것만큼 뺀다", () => {
  const failures = card("failures", {
    nowCount: 3,
    items: [
      item({ itemKey: "a", attention: "NOW" }),
      item({ itemKey: "b", attention: "NOW" }),
      item({ itemKey: "c", attention: "LATER" }),
    ],
  });
  assert.equal(visibleNowCount(failures, new Map()), 3);
  assert.equal(visibleNowCount(failures, new Map([["a", "hidden"]])), 2);
  // LATER 항목과 그리지 않는 항목의 제어는 수에 영향이 없다.
  assert.equal(
    visibleNowCount(failures, new Map([["c", "hidden"], ["gone", "snoozed"]])),
    3,
  );
  assert.equal(
    visibleNowCount(failures, new Map([["a", "hidden"], ["b", "snoozed"]])),
    1,
  );
});

test("카드 머리의 수는 0 아래로 내려가지 않는다", () => {
  const failures = card("failures", {
    nowCount: 0,
    items: [item({ itemKey: "a", attention: "NOW" })],
  });
  assert.equal(visibleNowCount(failures, new Map([["a", "hidden"]])), 0);
});

test("다시 읽은 응답에서 빠진 항목의 제어는 표에서 빠진다", () => {
  const controls = new Map([["a", "hidden"], ["b", "snoozed"]]);
  const pruned = pruneControls(controls, [item({ itemKey: "b" })]);
  assert.deepEqual([...pruned], [["b", "snoozed"]]);
  // 빠졌다가 같은 열쇠로 돌아와도 옛 제어가 되살아나지 않는다.
  assert.equal(pruned.has("a"), false);
});

test("뺄 열쇠가 없으면 받은 표를 그대로 돌려준다", () => {
  const controls = new Map([["a", "hidden"]]);
  assert.equal(pruneControls(controls, [item({ itemKey: "a" })]), controls);
  assert.equal(pruneControls(new Map(), []).size, 0);
});

test("「내일 아침」 은 서울 기준 다음 날 09:00 이다", () => {
  // 서울 23:30 이면 바로 다음 날 아침이다.
  assert.equal(snoozeUntil("tomorrow", new Date("2026-10-04T14:30:00Z")), "2026-10-05T00:00:00.000Z");
  // 서울에서 이미 날짜가 넘어간 01:30 이면 그다음 날 아침이다.
  assert.equal(snoozeUntil("tomorrow", new Date("2026-10-04T16:30:00Z")), "2026-10-06T00:00:00.000Z");
});

test("「일주일 뒤」 는 7일 뒤 같은 시각이다", () => {
  assert.equal(snoozeUntil("week", new Date("2026-10-04T01:00:00Z")), "2026-10-11T01:00:00.000Z");
});

test("기한 입력은 서울의 벽시계로 읽고 그대로 되돌린다", () => {
  assert.equal(seoulInputToIso("2026-10-05T18:00"), "2026-10-05T09:00:00.000Z");
  assert.equal(isoToSeoulInput("2026-10-05T09:00:00Z"), "2026-10-05T18:00");
  assert.equal(isoToSeoulInput(null), "");
});

test("빈 기한이나 형식이 틀린 기한은 없는 기한이다", () => {
  assert.equal(seoulInputToIso(""), null);
  assert.equal(seoulInputToIso("내일"), null);
});

test("항목의 단추는 now.md 「동작」 표를 따른다", () => {
  const conversationId = "7b1e0000-0000-4000-8000-000000000000";
  const followUp = {
    id: "0199a000-0000-7000-8000-000000000001",
    dueAt: null,
    waiting: false,
    proposed: true,
    agentProposed: true,
  };
  const labels = (target: AttentionItem) => itemActions(target).map((action) => [action.kind, action.label]);
  assert.deepEqual(labels(item({ why: why("FOLLOW_UP_PROPOSED", []), conversationId, followUp })), [
    ["accept", "받아들이기"],
    ["reject", "거절"],
    ["edit", "고치기"],
  ]);
  assert.deepEqual(labels(item({ why: why("FOLLOW_UP_OPEN", []), followUp: { ...followUp, proposed: false } })), [
    ["done", "끝냄"],
    ["drop", "그만둠"],
    ["edit", "고치기"],
  ]);
  assert.deepEqual(labels(item({ why: why("APPROVAL_PENDING", []), conversationId })), [["link", "대화에서 보기"]]);
  assert.deepEqual(labels(item({ why: why("DELIVERY_FAILED", ["DELIVERY_NOT_DONE"]), conversationId })), [
    ["link", "대화 열기"],
  ]);
  assert.deepEqual(labels(item({ why: why("CONVERSATION_RECENT", []), conversationId })), []);
});

test("먼저 다룰 문제는 점검 대화로 가는 링크와 두 반응 단추를 낸다", () => {
  const conversationId = "7b1e0000-0000-4000-8000-000000000000";
  const surfaced = item({
    why: why("PROBLEM_SURFACED", []),
    conversationId,
    problem: { decisionId: 7, level: "SURFACE", action: "영수증 사진을 정리해 두기" },
  });
  assert.equal(reasonText(surfaced.why), "에이전트가 먼저 다룰 문제로 골랐어요");
  assert.equal(itemHref(surfaced), `/chat/${conversationId}`);
  assert.deepEqual(
    itemActions(surfaced).map((action) => [action.kind, action.label]),
    [
      ["link", "점검 대화에서 보기"],
      ["react-accept", "받아들임"],
      ["react-dismiss", "관심 없음"],
    ],
  );
});

test("먼저 다룰 문제에 판정 칸이 없으면 단추를 내지 않고, 대화가 없으면 링크만 뺀다", () => {
  assert.deepEqual(itemActions(item({ why: why("PROBLEM_SURFACED", []), problem: null })), []);
  const noConversation = item({
    why: why("PROBLEM_SURFACED", []),
    conversationId: null,
    problem: { decisionId: 7, level: "ASK_APPROVAL", action: null },
  });
  assert.deepEqual(
    itemActions(noConversation).map((action) => action.kind),
    ["react-accept", "react-dismiss"],
  );
});

test("갈 곳이 없는 항목에는 링크 단추가 없다", () => {
  assert.deepEqual(itemActions(item({ why: why("APPROVAL_PENDING", []), conversationId: null })), []);
});

test("할 일 식별자가 없는 할 일 항목에는 단추가 없다", () => {
  for (const trigger of ["FOLLOW_UP_PROPOSED", "FOLLOW_UP_OPEN"]) {
    assert.deepEqual(itemActions(item({ why: why(trigger, []), followUp: null })), [], trigger);
  }
});

test("비율은 반올림한 퍼센트이고 전체가 0 이면 대시다", () => {
  assert.equal(ratioText(1, 4), "25%");
  assert.equal(ratioText(2, 3), "67%");
  assert.equal(ratioText(0, 5), "0%");
  assert.equal(ratioText(0, 0), "-");
});
