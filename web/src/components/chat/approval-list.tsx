"use client";

import { useEffect, useState } from "react";
import { ApprovalCard } from "@/components/chat/approval-card";
import { ApprovalGroup } from "@/components/chat/approval-group";
import { Notice } from "@/components/ui/notice";
import {
  approveConnectorAction,
  bulkApprovable,
  readConnectorActions,
  type ConnectorAction,
} from "@/lib/connector-action";

/** 카드로 보이는 상태다. 끝난 줄의 결과는 알림 줄과 답으로 이미 대화에 있다. */
const SHOWN = ["PENDING", "EXECUTING", "UNKNOWN"];

/** 「모두 승인」 이 다음 건으로 넘어가는 결과다. 그 밖의 결과(실패, 모름, 실행하지 않음)를 만나면 멈춘다. */
const CONTINUE = ["SUCCEEDED", "EXECUTING"];

type Loaded = { conversationId: string; actions: ConnectorAction[] };

/**
 * 기다리는 줄은 같은 커넥터의 같은 도구끼리 묶는다. 묶음의 자리는 그 도구가 처음 나온 자리다.
 * 실행 중이거나 결과를 모르는 줄과 도구 이름이 없는 줄은 한 장씩 둔다. 이름 없는 줄을 제목으로 묶으면 다른 도구가
 * 한 묶음에 섞일 수 있다.
 */
function grouped(
  actions: ConnectorAction[],
): { key: string; actions: ConnectorAction[] }[] {
  const groups = new Map<string, ConnectorAction[]>();
  const order: { key: string; actions: ConnectorAction[] }[] = [];
  for (const action of actions) {
    if (action.status !== "PENDING" || action.toolName === null) {
      order.push({ key: action.actionId, actions: [action] });
      continue;
    }
    const key = `${action.connectorId}\u0000${action.toolName}`;
    const group = groups.get(key);
    if (group) {
      group.push(action);
      continue;
    }
    const created = [action];
    groups.set(key, created);
    order.push({ key, actions: created });
  }
  return order;
}

/**
 * 묶음의 「모두 승인」 을 보낸다.
 *
 * <p>건마다 차례로 승인 요청을 보내고 기간은 싣지 않는다. 요청이 실패하거나, 실행했다고 볼 수 없는 결과가 오면
 * 거기서 멈추고 오류를 남긴다. 보내는 동안 그 묶음의 줄은 `locked` 가 참이다. 앞 건이 끝나 묶음이 한 장으로
 * 바뀌어도 남은 줄의 단추가 막혀 있다.
 */
function useBulkApproval(
  replace: (next: ConnectorAction) => void,
  reload: () => void,
) {
  const [bulk, setBulk] = useState<{ key: string; actionIds: string[] } | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);

  async function approveAll(key: string, group: ConnectorAction[]) {
    if (bulk) return;
    setBulk({ key, actionIds: group.map((action) => action.actionId) });
    setError(null);
    for (const action of group) {
      const result = await approveConnectorAction(action.actionId, null);
      if (!result.ok) {
        setError(result.message);
        reload();
        break;
      }
      replace(result.data);
      if (!CONTINUE.includes(result.data.status)) {
        setError(
          "한 건이 끝나지 않아 나머지는 승인하지 않았어요. 남은 건을 확인해 주세요.",
        );
        break;
      }
    }
    setBulk(null);
  }

  return {
    bulkKey: bulk?.key ?? null,
    locked: (actionId: string) => bulk?.actionIds.includes(actionId) ?? false,
    error,
    clearError: () => setError(null),
    approveAll,
  };
}

/**
 * 입력창 위에 그 대화의 승인 카드를 모아 보인다.
 *
 * <p>승인을 기다리는 동안에도 대화를 읽을 수 있게 이 영역은 화면 높이의 40% 까지만 차지하고 안에서 스크롤한다.
 * 같은 도구로 기다리는 줄이 여럿이면 `ApprovalGroup` 한 묶음으로 보인다.
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
  /** 펼친 묶음의 key 다. 묶음이 다시 그려지거나 한 장이 됐다가 다시 묶여도 펼친 상태가 남는다 */
  const [expanded, setExpanded] = useState<string[]>([]);
  /** 카드가 혼자 승인이나 거절을 보내는 중인 줄이다. 그 줄이 든 묶음의 「모두 승인」 을 막는다 */
  const [sending, setSending] = useState<string[]>([]);
  const bulk = useBulkApproval(replace, () => setReloads((count) => count + 1));

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readConnectorActions(conversationId).then((result) => {
      if (stale) return;
      // 한 번 못 읽었다고 떠 있던 카드를 치우지 않는다. 같은 대화의 앞선 목록을 그대로 둔다.
      setLoaded((current) =>
        result.ok
          ? { conversationId, actions: result.data }
          : current?.conversationId === conversationId
            ? current
            : { conversationId, actions: [] },
      );
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey, reloads]);

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

  function changed(next: ConnectorAction | null) {
    bulk.clearError();
    if (next) replace(next);
    else setReloads((count) => count + 1);
  }

  function dismiss(actionId: string) {
    setDismissed((current) => [...current, actionId]);
  }

  function sendingChange(actionId: string, on: boolean) {
    setSending((current) =>
      on
        ? [...current, actionId]
        : current.filter((value) => value !== actionId),
    );
  }

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

  return (
    <div
      data-testid="approval-list"
      role="region"
      aria-label="승인을 기다리는 동작"
      // 카드가 많아도 메시지 목록의 자리를 남긴다. 높이는 화면에 따라 이어지는 값이라 임의 값으로 둔다.
      className="mx-auto mb-2 flex max-h-[40dvh] w-full max-w-3xl flex-col gap-3 overflow-y-auto px-1"
    >
      {bulk.error ? (
        <Notice variant="error" role="alert" data-testid="approval-bulk-error">
          {bulk.error}
        </Notice>
      ) : null}
      {grouped(actions).map(({ key, actions: group }) =>
        group.length === 1 ? (
          <ApprovalCard
            key={group[0].actionId}
            action={group[0]}
            locked={bulk.locked(group[0].actionId)}
            onSendingChange={(on) => sendingChange(group[0].actionId, on)}
            onChanged={changed}
            onDismiss={() => dismiss(group[0].actionId)}
          />
        ) : (
          <ApprovalGroup
            key={key}
            actions={group}
            open={expanded.includes(key)}
            onOpenChange={(open) =>
              setExpanded((current) =>
                open
                  ? [...current, key]
                  : current.filter((value) => value !== key),
              )
            }
            bulk={group.every(bulkApprovable)}
            approvingAll={bulk.bulkKey === key}
            approveAllDisabled={
              bulk.bulkKey !== null ||
              group.some((action) => sending.includes(action.actionId))
            }
            onApproveAll={() => void bulk.approveAll(key, group)}
            locked={bulk.locked}
            onSendingChange={sendingChange}
            onChanged={changed}
            onDismiss={dismiss}
          />
        ),
      )}
    </div>
  );
}
