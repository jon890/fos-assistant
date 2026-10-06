"use client";

import { useId } from "react";
import { ApprovalCard } from "@/components/chat/approval-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { toolRiskLabel } from "@/lib/connection";
import { approvalArgs, type ConnectorAction } from "@/lib/connector-action";

/** 접힌 묶음에서 한 건을 가리키는 글이다. 위에 보이는 첫 인자의 값이고, 없으면 몇 번째인지다. */
function itemLabel(action: ConnectorAction, index: number): string {
  const first = approvalArgs(action.argsJson)?.rows.find((row) => row.core);
  return first ? first.value : `${index + 1}번째`;
}

/**
 * 같은 도구로 기다리는 승인 줄 여럿을 한 묶음으로 보인다.
 *
 * <p>접혀 있으면 도구 이름과 건수, 건마다 위에 보이는 첫 인자의 값만 보인다. 펼치면 건마다 인자와 승인, 거절
 * 단추가 있다. 「모두 승인」 은 펼친 뒤에만 보이고 `bulk` 가 참일 때만 둔다.
 *
 * <p>펼친 상태와 「모두 승인」 의 진행은 `ApprovalList` 가 갖는다. 앞 건을 처리해 묶음이 다시 그려지거나 한 장으로
 * 바뀌어도 그 상태가 남는다.
 */
export function ApprovalGroup({
  actions,
  open,
  onOpenChange,
  bulk,
  approvingAll,
  approveAllDisabled,
  onApproveAll,
  locked,
  onSendingChange,
  onChanged,
  onDismiss,
}: {
  actions: ConnectorAction[];
  open: boolean;
  onOpenChange(open: boolean): void;
  /** 묶음의 모든 건이 한꺼번에 승인할 수 있는 줄인가 */
  bulk: boolean;
  /** 이 묶음의 「모두 승인」 을 보내는 중인가 */
  approvingAll: boolean;
  /** 다른 승인이나 거절을 보내는 중이라 「모두 승인」 을 막는가 */
  approveAllDisabled: boolean;
  onApproveAll(): void;
  locked(actionId: string): boolean;
  onSendingChange(actionId: string, sending: boolean): void;
  onChanged(next: ConnectorAction | null): void;
  onDismiss(actionId: string): void;
}) {
  const bodyId = useId();
  const first = actions[0];
  const summary = actions.map(itemLabel).join(", ");

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
          aria-controls={bodyId}
          aria-label={`${first.title} ${actions.length}건 ${open ? "접기" : "펼치기"}`}
          onClick={() => onOpenChange(!open)}
        >
          {open ? "접기" : "펼치기"}
        </Button>
      </div>
      <div id={bodyId} className="flex min-w-0 flex-col gap-3">
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
                locked={locked(action.actionId)}
                onSendingChange={(sending) =>
                  onSendingChange(action.actionId, sending)
                }
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
                  disabled={approveAllDisabled}
                  loading={approvingAll}
                  onClick={onApproveAll}
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
            title={summary}
          >
            {summary}
          </p>
        )}
      </div>
    </section>
  );
}
