"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Notice } from "@/components/ui/notice";
import { toolRiskLabel } from "@/lib/connection";
import {
  approveConnectorAction,
  readableArgs,
  rejectConnectorAction,
  type ConnectorAction,
  type GrantPeriod,
} from "@/lib/connector-action";

const GRANT_CHOICES: { period: GrantPeriod; label: string }[] = [
  { period: "HOUR", label: "1시간 동안" },
  { period: "TODAY", label: "오늘 하루" },
  { period: "DAYS_30", label: "30일 동안" },
];

type Sending = "approve" | "reject" | "grant" | null;

/**
 * 에이전트가 하려는 동작 하나를 보이고 승인이나 거절을 받는다.
 *
 * <p>Control Plane 응답으로만 그린다. 인자는 남이 쓴 글이 섞일 수 있어 마크다운이나 HTML 로 읽지 않고 글자
 * 그대로 보인다. 도구의 원래 이름과 요청 번호 같은 내부 값은 그리지 않는다.
 *
 * <p>`onChanged` 에 바뀐 줄을 넘긴다. 이미 처리된 요청이면 `null` 을 넘겨 목록을 다시 읽게 한다.
 */
export function ApprovalCard({
  action,
  onChanged,
  onDismiss,
}: {
  action: ConnectorAction;
  onChanged(next: ConnectorAction | null): void;
  onDismiss(): void;
}) {
  const [sending, setSending] = useState<Sending>(null);
  const [error, setError] = useState<string | null>(null);
  const busy = sending !== null;

  async function send(kind: Exclude<Sending, null>, grant: GrantPeriod | null) {
    if (busy) return;
    setSending(kind);
    setError(null);
    const result =
      kind === "reject"
        ? await rejectConnectorAction(action.actionId)
        : await approveConnectorAction(action.actionId, grant);
    setSending(null);
    if (result.ok) return onChanged(result.data);
    setError(result.message);
    if (result.code === "CONNECTOR_ACTION_NOT_PENDING") onChanged(null);
  }

  const args = readableArgs(action.argsJson);

  return (
    <section
      data-testid="approval-card"
      data-status={action.status}
      className="flex min-w-0 flex-col gap-3 rounded-lg border border-border bg-card p-4 shadow-card"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="min-w-0 break-words text-sm font-medium">
          {action.title}
        </h3>
        <Badge variant="outline">
          {action.risk ? toolRiskLabel(action.risk) : "쓰기"}
        </Badge>
      </div>
      <p className="text-sm text-muted-foreground">
        에이전트가 이 동작을 하려고 해요. 내용을 확인해 주세요.
      </p>
      {args && args.length > 0 ? (
        <dl
          data-testid="approval-args"
          className="flex max-h-48 flex-col gap-2 overflow-y-auto rounded-md bg-muted px-3 py-2 text-sm"
        >
          {args.map((row) => (
            <div key={row.key} className="min-w-0">
              <dt className="break-all text-xs text-muted-foreground">
                {row.key}
              </dt>
              <dd className="whitespace-pre-wrap break-all">{row.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {args === null && action.argsJson ? (
        <p
          data-testid="approval-args"
          className="max-h-48 overflow-y-auto whitespace-pre-wrap break-all rounded-md bg-muted px-3 py-2 text-sm"
        >
          {action.argsJson}
        </p>
      ) : null}
      {action.status === "PENDING" ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            data-testid="approval-approve"
            disabled={busy}
            loading={sending === "approve"}
            onClick={() => void send("approve", null)}
          >
            승인
          </Button>
          <Button
            size="sm"
            variant="outline"
            data-testid="approval-reject"
            disabled={busy}
            loading={sending === "reject"}
            onClick={() => void send("reject", null)}
          >
            거절
          </Button>
          {action.grantAllowed ? (
            // 모달로 두면 열린 동안 메뉴 바깥을 누른 첫 클릭이 그 자리에 닿지 않는다.
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button
                  size="sm"
                  variant="ghost"
                  data-testid="approval-grant"
                  disabled={busy}
                  loading={sending === "grant"}
                >
                  승인하고 묻지 않기
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" aria-label="묻지 않을 기간">
                {GRANT_CHOICES.map((choice) => (
                  <DropdownMenuItem
                    key={choice.period}
                    onSelect={() => void send("grant", choice.period)}
                  >
                    {choice.label}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          ) : null}
        </div>
      ) : null}
      {action.status === "EXECUTING" ? (
        <Notice variant="info" role="status">
          실행하는 중이에요
        </Notice>
      ) : null}
      {action.status === "UNKNOWN" ? (
        <>
          <Notice variant="warning" role="status">
            실행했는지 알 수 없어요. 그 서비스에서 확인해 주세요. 다시 실행하지
            않아요.
          </Notice>
          <div>
            <Button
              size="sm"
              variant="outline"
              data-testid="approval-dismiss"
              onClick={onDismiss}
            >
              닫기
            </Button>
          </div>
        </>
      ) : null}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </section>
  );
}
