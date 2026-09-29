"use client";

import { useEffect, useRef, useState } from "react";
import type { StartersView } from "@/lib/agent";

/** 추천을 만드는 중이면 다시 읽는 간격이다 */
const RETRY_INTERVAL_MS = 2000;
/** 첫 읽기 뒤 다시 읽는 횟수의 상한이다. 그래도 만드는 중이면 자리를 비워 둔다 */
const MAX_RETRIES = 3;

/**
 * 고른 에이전트의 추천 질문을 읽는다.
 *
 * <p>Control Plane 이 추천을 만드는 중이면 `GENERATING` 으로 답하므로 2초 간격으로 세 번까지 다시 읽는다.
 * 읽지 못했거나 끝내 만드는 중이면 빈 목록이다. 에이전트를 바꾸거나 `code` 가 null 이 되면 이전 읽기를 멈추고,
 * 그 뒤에 도착한 옛 응답은 요청 순번으로 버린다. `code` 가 null 이면 읽지 않는다.
 */
export function useStarterSuggestions(code: string | null): string[] {
  const [loaded, setLoaded] = useState<{ code: string; prompts: string[] } | null>(null);
  /** 마지막으로 시작한 읽기의 순번이다. 에이전트가 바뀌면 올라 옛 응답을 버린다 */
  const latestRequest = useRef(0);

  useEffect(() => {
    if (code === null) return;
    const agentCode = code;
    const request = ++latestRequest.current;
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function load(retries: number): Promise<void> {
      let view: StartersView | null = null;
      try {
        const response = await fetch(`/api/agents/${agentCode}/starters`, { cache: "no-store" });
        if (response.ok) view = (await response.json()) as StartersView;
      } catch {
        // 추천은 없어도 대화를 시작할 수 있다. 읽지 못하면 자리를 비워 둔다.
      }
      if (request !== latestRequest.current || view === null) return;
      if (view.status === "GENERATING") {
        if (retries < MAX_RETRIES) timer = setTimeout(() => void load(retries + 1), RETRY_INTERVAL_MS);
        return;
      }
      setLoaded({ code: agentCode, prompts: view.prompts });
    }

    void load(0);
    return () => {
      // 에이전트가 바뀌거나 화면이 사라지면 대기 중인 다시 읽기와 나간 응답을 모두 무효로 한다.
      latestRequest.current++;
      clearTimeout(timer);
    };
  }, [code]);

  // 다른 에이전트의 추천이 잠시라도 보이지 않게 읽은 에이전트와 맞을 때만 돌려준다.
  return loaded !== null && loaded.code === code ? loaded.prompts : [];
}
