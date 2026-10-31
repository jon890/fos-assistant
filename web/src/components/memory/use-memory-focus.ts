import { useEffect, useState } from "react";
import type { MemoryFilter } from "./memory-filter";
import type { MemoryTab } from "./memory-tabs";

/**
 * 주소의 `memory` 값이 가리키는 기억 줄을 펼치고 스크롤한다.
 *
 * <p>주소 값이 바뀐 렌더에서 한 번만 상태를 바꾼다. 줄이 걸러져 있지 않도록 필터를 「전체」 로 돌리고 기억 탭을 연다.
 * 줄이 목록에 없어도(대체되었거나 권한이 없음) 필터와 탭은 바뀐다. 펼칠 줄과 스크롤할 줄이 없을 뿐 오류는 아니다.
 */
export function useMemoryFocus(
  focusMemoryId: number | undefined,
  actions: {
    setOpenId(id: number): void;
    setTab(tab: MemoryTab): void;
    setFilter(filter: MemoryFilter): void;
  },
) {
  const [seen, setSeen] = useState<number>();
  if (focusMemoryId !== seen) {
    setSeen(focusMemoryId);
    if (focusMemoryId !== undefined) {
      actions.setOpenId(focusMemoryId);
      actions.setTab("memory");
      actions.setFilter("ALL");
    }
  }
  useEffect(() => {
    if (focusMemoryId === undefined) return;
    document
      .getElementById(`memory-${focusMemoryId}`)
      ?.scrollIntoView({ block: "center" });
  }, [focusMemoryId]);
}
