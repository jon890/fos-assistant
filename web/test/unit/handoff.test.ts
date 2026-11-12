import assert from "node:assert/strict";
import { test } from "node:test";
import {
  HANDOFF_NEEDS,
  HANDOFF_TEXT,
  handoffBrowserHref,
  newHandoffs,
  rebaseHandoffWatch,
  safeHandoff,
  watchHandoffTab,
  type Handoff,
} from "../../src/lib/handoff.ts";

const row = {
  handoffId: "0190f5b4-0000-7000-8000-000000000001",
  connectorId: "naver-shopping",
  connectorTitle: "네이버 쇼핑",
  need: "LOGIN",
  tab: "ABCDEF0123456789",
  createdAt: "2026-11-10T00:00:00Z",
};

test("계약대로 온 줄만 카드로 읽고, 단계 밖이나 탭 모양 밖이면 버린다", () => {
  assert.deepEqual(safeHandoff(row), row);
  assert.equal(safeHandoff({ ...row, need: "PAYMENT" }), null);
  assert.equal(safeHandoff({ ...row, tab: "../json/close" }), null);
  assert.equal(safeHandoff({ ...row, connectorTitle: 3 }), null);
  assert.equal(safeHandoff(null), null);
  assert.equal(safeHandoff([row]), null);
});

test("봇 탐지와 예상 밖 화면 줄도 카드로 읽고 정해 둔 제목을 쓴다", () => {
  assert.equal(safeHandoff({ ...row, need: "BOT_CHECK" })?.need, "BOT_CHECK");
  assert.equal(
    safeHandoff({ ...row, need: "UNEXPECTED_PAGE" })?.need,
    "UNEXPECTED_PAGE",
  );
  assert.equal(safeHandoff({ ...row, need: "bot_check" }), null);
  assert.equal(
    HANDOFF_TEXT.BOT_CHECK.title,
    "「내 브라우저」에서 자동 접속 확인을 풀어 주세요",
  );
  assert.equal(
    HANDOFF_TEXT.UNEXPECTED_PAGE.title,
    "「내 브라우저」에서 화면을 확인해 주세요",
  );
});

test("「내 브라우저 열기」 는 그 탭을 고른 채 내 브라우저 화면으로 간다", () => {
  const handoff = safeHandoff(row)!;
  assert.equal(handoffBrowserHref(handoff), "/browser?tab=ABCDEF0123456789");
});

test("단계마다 카드 제목과 「다 했어요」 글이 있고 글은 이어 가 달라는 말로 끝난다", () => {
  for (const need of HANDOFF_NEEDS) {
    assert.ok(HANDOFF_TEXT[need].title.startsWith("「내 브라우저」에서"));
    assert.ok(
      HANDOFF_TEXT[need].done.endsWith("멈춘 단계부터 이어서 해 주세요."),
    );
  }
});

const TAB = "ABCDEF0123456789";
const tabs = (url: string) => [
  { id: "OTHER", url: "https://example.com/other" },
  { id: TAB, url },
];

test("첫 탭 목록에 넘긴 탭이 있으면 그 주소를 기준으로 삼고, 없으면 더 묻지 않는다", () => {
  assert.deepEqual(watchHandoffTab({ phase: "waiting" }, TAB, tabs("https://nid.example/login#top")), {
    state: { phase: "watching", base: "https://nid.example/login" },
    changed: false,
  });
  assert.deepEqual(watchHandoffTab({ phase: "waiting" }, TAB, [{ id: "OTHER", url: "https://example.com" }]), {
    state: { phase: "off" },
    changed: false,
  });
});

test("넘긴 탭의 주소가 기준과 다르거나 탭이 닫히면 묻고, # 뒤만 다르면 묻지 않는다", () => {
  const watching = { phase: "watching", base: "https://nid.example/login" } as const;
  assert.equal(watchHandoffTab(watching, TAB, tabs("https://nid.example/login")).changed, false);
  assert.equal(watchHandoffTab(watching, TAB, tabs("https://nid.example/login#step2")).changed, false);
  assert.equal(watchHandoffTab(watching, TAB, tabs("https://shop.example/list")).changed, true);
  assert.equal(watchHandoffTab(watching, TAB, [{ id: "OTHER", url: "https://nid.example/login" }]).changed, true);
  assert.deepEqual(watchHandoffTab(watching, TAB, tabs("https://shop.example/list")).state, watching);
});

test("묻지 않기로 한 상태는 무엇을 받아도 그대로다", () => {
  assert.deepEqual(watchHandoffTab({ phase: "off" }, TAB, tabs("https://shop.example")), {
    state: { phase: "off" },
    changed: false,
  });
});

test("「아직이에요」 는 지금 주소를 새 기준으로 삼고, 넘긴 탭이 없으면 더 묻지 않는다", () => {
  assert.deepEqual(rebaseHandoffWatch(TAB, tabs("https://nid.example/otp#a")), {
    phase: "watching",
    base: "https://nid.example/otp",
  });
  assert.deepEqual(rebaseHandoffWatch(TAB, []), { phase: "off" });
});

test("처음 읽은 번호에 없는 넘기기만 새 넘기기로 보고 받은 순서를 지킨다", () => {
  const first = { ...row, handoffId: "a" } as Handoff;
  const second = { ...row, handoffId: "b" } as Handoff;
  const third = { ...row, handoffId: "c" } as Handoff;
  assert.deepEqual(newHandoffs(new Set(["b"]), [third, second, first]), [third, first]);
  assert.deepEqual(newHandoffs(new Set(["a"]), [first]), []);
});
