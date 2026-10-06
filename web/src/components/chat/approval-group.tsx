"use client";

import { useState } from "react";
import { ApprovalCard } from "@/components/chat/approval-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { toolRiskLabel } from "@/lib/connection";
import {
  approvalArgs,
  approveConnectorAction,
  bulkApprovable,
  type ConnectorAction,
} from "@/lib/connector-action";

/** 접힌 묶음에서 한 건을 가리키는 글이다. 위에 보이는 첫 인자의 값이고, 없으면 몇 번째인지다. */
function itemLabel(action: ConnectorAction, index: number): string {
  const first = approvalArgs(action.argsJson)?.rows.find((row) => row.core);
  return first ? first.value : `${index + 1}번째`;
}

/**
 * 같은 도구로 기다리는 승인 줄 여럿을 한 묶음으로 보인다.
 *
 * <p>처음에는 접혀 있고 도구 이름과 건수, 건마다 위에 보이는 첫 인자의 값만 보인다. 펼치면 건마다 인자와 승인,
 * 거절 단추가 있다. 「모두 승인」 은 펼친 뒤에만 보이고, 묶음의 모든 건이 `bulkApprovable` 일 때만 둔다. 한 건씩
 * 차례로 승인 요청을 보내며, 실패하면 거기서 멈추고 목록을 다시 읽는다.
 */
export function ApprovalGroup({
  actions,
  onChanged,
  onDismiss,
}: {
  actions: ConnectorAction[];
  onChanged(next: ConnectorAction | null): void;
  onDismiss(actionId: string): void;
}) {
  const [open, setOpen] = useState(false);
  const [approvingAll, setApprovingAll] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const first = actions[0];
  const bulk = actions.every(bulkApprovable);

  async function approveAll() {
    if (approvingAll) return;
    setApprovingAll(true);
    setError(null);
    for (const action of actions) {
      const result = await approveConnectorAction(action.actionId, null);
      if (!result.ok) {
        setError(result.message);
        onChanged(null);
        break;
      }
      onChanged(result.data);
    }
    setApprovingAll(false);
  }

  return (
    <section
      data-testid="approval-group"
      data-count={actions.length}
      className="flex min-w-0 flex-col gap-3 rounded-lg border border-border bg-card p-4 shadow-card"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="min-w-0 break-words text-sm font-medium">
          {first.title}
        </h3>
        <Badge variant="outline">
          {first.risk ? toolRiskLabel(first.risk) : "쓰기"}
        </Badge>
        <span className="text-sm text-muted-foreground">
          {actions.length}건
        </span>
        <Button
          size="sm"
          variant="ghost"
          className="ml-auto"
          data-testid="approval-group-toggle"
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? "접기" : "펼치기"}
        </Button>
      </div>
      {open ? (
        <>
          <p className="text-sm text-muted-foreground">
            에이전트가 이 동작들을 하려고 해요. 한 건씩 내용을 확인해 주세요.
          </p>
          {actions.map((action) => (
            <ApprovalCard
              key={action.actionId}
              action={action}
              grouped
              locked={approvingAll}
              onChanged={onChanged}
              onDismiss={() => onDismiss(action.actionId)}
            />
          ))}
          {bulk ? (
            <div className="border-t border-border pt-3">
              <Button
                size="sm"
                variant="outline"
                data-testid="approval-approve-all"
                loading={approvingAll}
                onClick={() => void approveAll()}
              >
                {actions.length}건 모두 승인
              </Button>
            </div>
          ) : null}
        </>
      ) : (
        <p
          data-testid="approval-group-summary"
          className="truncate text-sm text-muted-foreground"
        >
          {actions.map(itemLabel).join(", ")}
        </p>
      )}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </section>
  );
}
