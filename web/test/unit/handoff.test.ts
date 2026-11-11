import assert from "node:assert/strict";
import { test } from "node:test";
import {
  HANDOFF_NEEDS,
  HANDOFF_TEXT,
  handoffBrowserHref,
  safeHandoff,
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
