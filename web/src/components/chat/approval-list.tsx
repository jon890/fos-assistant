"use client";

import { useEffect, useState } from "react";
import { ApprovalCard } from "@/components/chat/approval-card";
import {
  readConnectorActions,
  type ConnectorAction,
} from "@/lib/connector-action";

/** 카드로 보이는 상태다. 끝난 줄의 결과는 알림 줄과 답으로 이미 대화에 있다. */
const SHOWN = ["PENDING", "EXECUTING", "UNKNOWN"];

type Loaded = { conversationId: string; actions: ConnectorAction[] };

/**
 * 입력창 위에 그 대화의 승인 카드를 모아 보인다.
 *
 * <p>대화나 `refreshKey` 가 바뀌면 다시 읽는다. 보일 줄이 없으면 아무것도 그리지 않는다. 읽지 못해도 대화를
 * 막지 않게 조용히 비운다.
 */
export function ApprovalList({
  conversationId,
  refreshKey,
}: {
  conversationId: string | null;
  refreshKey: number;
}) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  /** 이미 처리된 요청을 만난 카드가 다시 읽기를 청한 횟수다 */
  const [reloads, setReloads] = useState(0);
  /** 사용자가 닫은 카드다. 서버 상태를 바꾸지 않으므로 새로 고치면 다시 보인다 */
  const [dismissed, setDismissed] = useState<string[]>([]);

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readConnectorActions(conversationId).then((result) => {
      if (stale) return;
      setLoaded({ conversationId, actions: result.ok ? result.data : [] });
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey, reloads]);

  // 앞선 대화에서 읽은 줄을 다른 대화에 그리지 않는다.
  const actions =
    loaded !== null && loaded.conversationId === conversationId
      ? loaded.actions.filter(
          (action) =>
            SHOWN.includes(action.status) &&
            !dismissed.includes(action.actionId),
        )
      : [];
  if (actions.length === 0) return null;

  function replace(next: ConnectorAction) {
    setLoaded(
      (current) =>
        current && {
          ...current,
          actions: current.actions.map((action) =>
            action.actionId === next.actionId ? next : action,
          ),
        },
    );
  }

  return (
    <div
      data-testid="approval-list"
      className="mx-auto mb-2 flex w-full max-w-3xl flex-col gap-3 px-1"
    >
      {actions.map((action) => (
        <ApprovalCard
          key={action.actionId}
          action={action}
          onChanged={(next) =>
            next ? replace(next) : setReloads((count) => count + 1)
          }
          onDismiss={() =>
            setDismissed((current) => [...current, action.actionId])
          }
        />
      ))}
    </div>
  );
}
