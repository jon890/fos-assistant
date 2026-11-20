"use client";

import { useSyncExternalStore } from "react";

/** 화면 틀이 마지막으로 본 대화의 경로를 적어 두는 `sessionStorage` 의 이름이다. */
export const LAST_CONVERSATION_KEY = "last-conversation-path";

/** 적어 둔 대화가 없거나 저장소를 읽지 못하면 새 대화 화면으로 돌아간다. */
function readBackHref(): string {
  try {
    const stored = sessionStorage.getItem(LAST_CONVERSATION_KEY);
    return stored?.startsWith("/chat/") ? stored : "/";
  } catch {
    return "/";
  }
}

// 이 탭의 `sessionStorage` 는 대화 밖의 틀에 머무는 동안 바뀌지 않으므로 구독할 것이 없다.
function subscribeNothing(): () => void {
  return () => {};
}

/**
 * 관리자 틀의 「사용 화면으로 돌아가기」 와 설정 틀의 「대화로」 가 갈 곳이다.
 *
 * <p>서버의 첫 그림과 hydration 동안은 `/` 이고, 브라우저에서 적어 둔 대화를 읽는다.
 */
export function useBackToChatHref(): string {
  return useSyncExternalStore(subscribeNothing, readBackHref, () => "/");
}
