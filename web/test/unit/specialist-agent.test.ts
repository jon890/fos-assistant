import assert from "node:assert/strict";
import test from "node:test";
import type { BoundAgent } from "../../src/lib/connection.ts";
import {
  DETACH_LOCKED_REASON,
  createdMessage,
  detachOption,
  shouldOfferSpecialist,
  specialistDraft,
} from "../../src/lib/specialist-agent.ts";
import { AGENT_ICONS } from "../../src/lib/agent-badge.ts";

function bound(agentName: string): BoundAgent {
  return {
    agentCode: `a-${agentName.length}`,
    agentName,
    status: "READY",
    restartRequired: false,
  };
}

test("방금 연결을 마쳤고 붙은 곳이 없을 때만 제안한다", () => {
  const base = { justConnected: true, bindings: [], preferredAgent: null };
  assert.equal(shouldOfferSpecialist(base), true);
  assert.equal(
    shouldOfferSpecialist({ ...base, justConnected: false }),
    false,
    "이미 있던 연결",
  );
  assert.equal(
    shouldOfferSpecialist({ ...base, bindings: [bound("쇼핑 담당")] }),
    false,
    "그 서비스가 붙은 에이전트가 있다",
  );
  assert.equal(
    shouldOfferSpecialist({ ...base, preferredAgent: "a1" }),
    false,
    "에이전트 화면에서 그 에이전트에 붙이러 왔다",
  );
});

test("미리 채운 이름과 소개는 플러그인 이름으로, 아이콘은 플러그인마다 정한 값이나 기본값이다", () => {
  assert.deepEqual(specialistDraft("naver-shopping", "네이버 쇼핑"), {
    name: "네이버 쇼핑 담당",
    description: "네이버 쇼핑 일을 맡아요",
    icon: "🛍️",
  });
  assert.equal(specialistDraft("unknown-plugin", "새 서비스").icon, "🤖");
});

test("미리 고른 아이콘은 서버가 받는 아이콘 목록 안에 있다", () => {
  for (const id of [
    "naver-shopping",
    "korail-ktx",
    "gmail",
    "naver-blog",
    "tossinvest",
    "unknown-plugin",
  ]) {
    assert.ok(
      (AGENT_ICONS as readonly string[]).includes(specialistDraft(id, id).icon),
      id,
    );
  }
});

test("떼기 칸은 붙은 에이전트가 있을 때만 있고 기본 비서는 「비서」 로 보인다", () => {
  assert.equal(detachOption([], false), null);
  assert.deepEqual(detachOption([bound("비서")], false), {
    label: "「비서」에서는 떼고 맡기기만",
    locked: false,
  });
  assert.equal(
    detachOption([bound("비서"), bound("가계부")], false)?.label,
    "「비서」, 「가계부」에서는 떼고 맡기기만",
  );
});

test("single_binding 이면 떼기 칸을 잠근다", () => {
  assert.equal(detachOption([bound("비서")], true)?.locked, true);
  assert.match(DETACH_LOCKED_REASON, /하나에만/);
});

test("만든 뒤 안내의 조사는 이름 끝 글자로 정한다", () => {
  assert.equal(
    createdMessage("쇼핑 담당", "네이버 쇼핑"),
    "「쇼핑 담당」을 만들고 「네이버 쇼핑」을 붙였어요.",
  );
  assert.equal(
    createdMessage("메일 비서", "Gmail"),
    "「메일 비서」를 만들고 「Gmail」을 붙였어요.",
  );
});
