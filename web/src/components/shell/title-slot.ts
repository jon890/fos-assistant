"use client";

import { createContext, useContext, useEffect, useMemo, useState } from "react";

/**
 * 좁은 화면 위 막대 가운데 자리다. 화면이 이 자리를 차지하면 틀은 제목 링크 대신 화면이 보낸 단추를 보인다.
 *
 * <p>단추는 화면의 React 트리 안에 두고 portal 로만 이 자리에 그린다. 상태와 콜백이 화면에 남아 틀이 화면의
 * 값을 들고 있지 않는다. 새 대화 화면의 이름표가 쓴다(docs/features/app-shell/spec.md 의 REQ9).
 */
export type TitleSlot = {
  element: HTMLElement | null;
  claim(claimed: boolean): void;
};

export const TitleSlotContext = createContext<TitleSlot | null>(null);

/** 위 막대 가운데 자리를 차지하고 그 요소를 돌려준다. 사이드바 틀 밖이거나 아직 그리기 전이면 null 이다 */
export function useShellTitleSlot(): HTMLElement | null {
  const slot = useContext(TitleSlotContext);
  const claim = slot?.claim;
  useEffect(() => {
    if (!claim) return;
    claim(true);
    return () => claim(false);
  }, [claim]);
  return slot?.element ?? null;
}

/** 틀이 위 막대 가운데 자리의 요소와 차지 여부를 갖는다. `value` 를 context 로 내리고 나머지로 위 막대를 그린다 */
export function useTitleSlotState() {
  const [element, setElement] = useState<HTMLElement | null>(null);
  const [claimed, setClaimed] = useState(false);
  const value = useMemo<TitleSlot>(
    () => ({ element, claim: setClaimed }),
    [element],
  );
  return { value, setElement, claimed };
}
