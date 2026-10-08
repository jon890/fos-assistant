import assert from "node:assert/strict";
import test from "node:test";
import {
  changeLabel,
  countedForLabel,
  draftChanged,
  draftOf,
  grantsOf,
  missingNotice,
  type AgentMemoryChange,
  type AgentMemoryCollection,
  type AgentMemorySetting,
} from "../../web/src/lib/agent-memory.ts";

function collection(
  key: string,
  overrides: Partial<AgentMemoryCollection> = {},
): AgentMemoryCollection {
  return {
    key,
    displayName: key,
    listed: true,
    granted: false,
    allowSensitive: false,
    entryCount: 0,
    sensitiveEntryCount: 0,
    ...overrides,
  };
}

function setting(collections: AgentMemoryCollection[]): AgentMemorySetting {
  return { countedFor: "OWNER", ownerName: "사용자A", collections, changes: [] };
}

test("받지 않는 영역에 항목이 있으면 받지 않는다고 알린다", () => {
  const career = collection("career", { entryCount: 3 });
  assert.equal(
    missingNotice(career, { granted: false, allowSensitive: false }),
    "항목 3개가 있지만 받지 않아요.",
  );
  assert.equal(
    missingNotice(collection("career"), { granted: false, allowSensitive: false }),
    null,
  );
});

test("받지만 민감 허용이 꺼진 영역의 민감 항목은 받지 않는다고 알리고 허용하면 알리지 않는다", () => {
  const career = collection("career", { entryCount: 3, sensitiveEntryCount: 1 });
  assert.equal(
    missingNotice(career, { granted: true, allowSensitive: false }),
    "민감 항목 1개는 받지 않아요.",
  );
  assert.equal(missingNotice(career, { granted: true, allowSensitive: true }), null);
});

test("고른 값이 아니라 초안으로 안내가 바뀐다", () => {
  const career = collection("career", {
    granted: true,
    allowSensitive: true,
    entryCount: 2,
    sensitiveEntryCount: 1,
  });
  // 저장된 값은 모두 받는 상태지만 초안에서 받음을 끄면 안내가 나온다.
  assert.equal(
    missingNotice(career, { granted: false, allowSensitive: false }),
    "항목 2개가 있지만 받지 않아요.",
  );
});

test("받는 영역만 key 순서로 낸다", () => {
  assert.deepEqual(
    grantsOf({
      identity: { granted: true, allowSensitive: false },
      career: { granted: true, allowSensitive: true },
      core: { granted: false, allowSensitive: false },
    }),
    [
      { collection: "career", allowSensitive: true },
      { collection: "identity", allowSensitive: false },
    ],
  );
  assert.deepEqual(grantsOf({}), []);
});

test("초안이 저장된 값과 같으면 바뀌지 않았다고 본다", () => {
  const current = setting([
    collection("core", { granted: true }),
    collection("career", { entryCount: 3 }),
  ]);
  assert.equal(draftChanged(current, draftOf(current)), false);
});

test("받음이 바뀌거나 민감 허용 하나만 바뀌어도 바뀌었다고 본다", () => {
  const current = setting([
    collection("core", { granted: true }),
    collection("career"),
  ]);
  const grantedCareer = {
    ...draftOf(current),
    career: { granted: true, allowSensitive: false },
  };
  assert.equal(draftChanged(current, grantedCareer), true);
  const sensitiveCore = {
    ...draftOf(current),
    core: { granted: true, allowSensitive: true },
  };
  assert.equal(draftChanged(current, sensitiveCore), true);
});

test("수를 센 대상을 주인이 있을 때와 없을 때로 알린다", () => {
  assert.equal(
    countedForLabel({ countedFor: "OWNER", ownerName: "사용자A", collections: [], changes: [] }),
    "주인(사용자A)의 기억과 그룹 기억을 셌어요.",
  );
  assert.equal(
    countedForLabel({ countedFor: "GROUP", ownerName: null, collections: [], changes: [] }),
    "주인이 없어 그룹 기억만 셌어요.",
  );
});

test("변경 기록의 설명을 다섯 경우로 쓴다", () => {
  function change(
    changeType: AgentMemoryChange["changeType"],
    allowSensitive: boolean,
  ): AgentMemoryChange {
    return {
      collection: "career",
      changeType,
      allowSensitive,
      changedByName: null,
      changedAt: "2026-10-08T05:00:00Z",
    };
  }
  assert.equal(changeLabel(change("GRANTED", false), "커리어"), "커리어 붙임");
  assert.equal(
    changeLabel(change("GRANTED", true), "커리어"),
    "커리어 붙임(민감 항목 허용)",
  );
  assert.equal(changeLabel(change("REVOKED", false), "커리어"), "커리어 뗌");
  assert.equal(
    changeLabel(change("SENSITIVE_CHANGED", true), "커리어"),
    "커리어 민감 항목 허용",
  );
  assert.equal(
    changeLabel(change("SENSITIVE_CHANGED", false), "커리어"),
    "커리어 민감 항목 허용 끔",
  );
});
