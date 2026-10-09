/** 에이전트가 받는 Memory 영역의 관리 화면 값이다. 칸의 뜻은 `backend/docs/flow.md` 가 갖는다. */

export type AgentMemoryCollection = {
  key: string;
  displayName: string;
  /** 그룹의 영역 목록에 있는 영역이다. 거짓이면 목록에서 빠졌지만 아직 받고 있는 영역이다. */
  listed: boolean;
  granted: boolean;
  allowSensitive: boolean;
  /** 그 영역에서 실릴 수 있는 항목 수다. 민감 항목도 센다. */
  entryCount: number;
  sensitiveEntryCount: number;
};

export type AgentMemoryChange = {
  collection: string;
  changeType: "GRANTED" | "REVOKED" | "SENSITIVE_CHANGED";
  allowSensitive: boolean;
  changedByName: string | null;
  changedAt: string;
};

export type AgentMemorySetting = {
  /** 수를 센 대상이다. `OWNER` 는 주인의 기억과 그룹 기억, `GROUP` 은 그룹 기억만이다. */
  countedFor: "OWNER" | "GROUP";
  ownerName: string | null;
  collections: AgentMemoryCollection[];
  changes: AgentMemoryChange[];
};

/** 관리자가 고르는 중인 값이다. 영역의 key 마다 받음과 민감 허용을 갖는다. */
export type AgentMemoryDraft = Record<
  string,
  { granted: boolean; allowSensitive: boolean }
>;

/** 받지 않는 영역의 민감 허용은 뜻이 없어 거짓으로 맞춘다. */
function normalized(row: { granted: boolean; allowSensitive: boolean }) {
  return {
    granted: row.granted,
    allowSensitive: row.granted && row.allowSensitive,
  };
}

export function draftOf(setting: AgentMemorySetting): AgentMemoryDraft {
  return Object.fromEntries(
    setting.collections.map((collection) => [
      collection.key,
      normalized(collection),
    ]),
  );
}

/** 저장 요청에 보낼 값이다. 받는 영역만 key 순서로 낸다. */
export function grantsOf(
  draft: AgentMemoryDraft,
): { collection: string; allowSensitive: boolean }[] {
  return Object.keys(draft)
    .sort()
    .filter((key) => draft[key].granted)
    .map((key) => ({
      collection: key,
      allowSensitive: draft[key].allowSensitive,
    }));
}

export function draftChanged(
  setting: AgentMemorySetting,
  draft: AgentMemoryDraft,
): boolean {
  return setting.collections.some((collection) => {
    const stored = normalized(collection);
    const picked = draft[collection.key]
      ? normalized(draft[collection.key])
      : stored;
    return (
      stored.granted !== picked.granted ||
      stored.allowSensitive !== picked.allowSensitive
    );
  });
}

/** 지금 고른 값 기준으로 이 영역에서 대화에 실리지 않을 항목이 있으면 알리는 문구다. */
export function missingNotice(
  collection: AgentMemoryCollection,
  row: { granted: boolean; allowSensitive: boolean },
): string | null {
  if (!row.granted) {
    return collection.entryCount > 0
      ? `항목 ${collection.entryCount}개가 있지만 받지 않아요.`
      : null;
  }
  if (!row.allowSensitive && collection.sensitiveEntryCount > 0) {
    return `민감 항목 ${collection.sensitiveEntryCount}개는 받지 않아요.`;
  }
  return null;
}

export function countedForLabel(setting: AgentMemorySetting): string {
  return setting.countedFor === "OWNER" && setting.ownerName !== null
    ? `주인(${setting.ownerName})의 기억과 그룹 기억을 셌어요.`
    : "주인이 없어 그룹 기억만 셌어요.";
}

/** 최근 변경 한 줄의 설명이다. `displayName` 은 지금 응답에서 그 영역의 이름이고, 없으면 key 를 넘긴다. */
export function changeLabel(
  change: AgentMemoryChange,
  displayName: string,
): string {
  switch (change.changeType) {
    case "GRANTED":
      return change.allowSensitive
        ? `${displayName} 붙임(민감 항목 허용)`
        : `${displayName} 붙임`;
    case "REVOKED":
      return `${displayName} 뗌`;
    case "SENSITIVE_CHANGED":
      return change.allowSensitive
        ? `${displayName} 민감 항목 허용`
        : `${displayName} 민감 항목 허용 끔`;
  }
}
