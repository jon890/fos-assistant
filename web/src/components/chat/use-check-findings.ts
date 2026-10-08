"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { readCheckFindings, type CheckFinding } from "@/lib/check-finding-api";

type Loaded = {
  conversationId: string;
  dismissWindowDays: number;
  findings: CheckFinding[];
};

/** 마지막 답의 발견이 아직 없을 때 차례로 기다렸다 다시 읽는 간격이다. */
const RETRY_DELAYS_MS = [500, 1000, 2000];

/**
 * 점검 대화의 발견과 지금 반응을 읽는다.
 *
 * <p>`enabled` 가 거짓이면 부르지 않고 빈 목록이다. 대화나 `refreshKey` 가 바뀌면 다시 읽는다.
 * 읽지 못해도 대화를 막지 않게 조용히 앞의 목록을 둔다. 앞선 대화의 발견을 다른 대화에 돌려주지 않는다.
 *
 * <p>backend 는 대화 SSE 의 `done` 을 보낸 뒤에 발견을 저장한다. 그래서 이력이 바뀐 직후 읽은 목록에
 * 마지막 답(`lastAnswerExecutionId`)의 발견이 없으면 정해 둔 간격으로 몇 번 더 읽고, 찾으면 멈춘다.
 * 「새로 알릴 것」 이 없는 답이면 그 횟수를 다 읽고 끝난다.
 *
 * <p>읽기마다 순번을 매기고 마지막에 보낸 읽기의 응답만 목록에 넣는다. 반응을 잇달아 누르거나 다시 읽기와
 * 겹쳐 앞선 응답이 늦게 와도 그 오래된 목록이 화면을 덮지 않는다.
 */
export function useCheckFindings(
  conversationId: string | null,
  enabled: boolean,
  refreshKey: string,
  lastAnswerExecutionId: number | null,
) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  /** 마지막에 보낸 읽기의 순번이다. effect 의 읽기와 `changed()` 의 읽기가 함께 센다 */
  const latestRead = useRef(0);

  useEffect(() => {
    if (conversationId === null || !enabled) return;
    let stale = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = (attempt: number) => {
      const sequence = ++latestRead.current;
      void readCheckFindings(conversationId).then((result) => {
        if (stale) return;
        if (result.ok && sequence === latestRead.current)
          setLoaded({ conversationId, ...result.data });
        const found =
          lastAnswerExecutionId === null ||
          (result.ok &&
            result.data.findings.some(
              (finding) => finding.executionId === lastAnswerExecutionId,
            ));
        if (found || attempt >= RETRY_DELAYS_MS.length) return;
        timer = setTimeout(() => read(attempt + 1), RETRY_DELAYS_MS[attempt]);
      });
    };
    read(0);
    return () => {
      stale = true;
      clearTimeout(timer);
    };
  }, [conversationId, enabled, refreshKey, lastAnswerExecutionId]);

  /**
   * 반응을 남긴 뒤에 부른다. 서버 목록을 한 번만 다시 읽는다.
   *
   * <p>반응은 이미 그린 발견에 남기므로 늦게 저장되는 발견을 기다리는 다시 읽기를 하지 않는다.
   * 그사이 다른 대화로 옮겼으면 그 대화의 읽기가 순번을 올려 이 응답을 버린다.
   */
  const changed = useCallback(() => {
    if (conversationId === null || !enabled) return;
    const sequence = ++latestRead.current;
    void readCheckFindings(conversationId).then((result) => {
      if (result.ok && sequence === latestRead.current)
        setLoaded({ conversationId, ...result.data });
    });
  }, [conversationId, enabled]);

  const current =
    enabled && loaded !== null && loaded.conversationId === conversationId
      ? loaded
      : null;
  return {
    findings: current?.findings ?? [],
    // 발견이 없으면 안내를 그리지 않으므로 읽기 전의 0 은 화면에 나오지 않는다.
    dismissWindowDays: current?.dismissWindowDays ?? 0,
    changed,
  };
}
