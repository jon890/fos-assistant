"use client";

import { useState } from "react";
import { cn } from "cn";
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
  approvalArgs,
  approveConnectorAction,
  hiddenArgKeys,
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

function ArgRows({ rows }: { rows: { key: string; value: string }[] }) {
  return rows.map((row) => (
    <div key={row.key} className="min-w-0">
      <dt className="break-all text-xs text-muted-foreground">{row.key}</dt>
      <dd className="whitespace-pre-wrap break-all">{row.value}</dd>
    </div>
  ));
}

/**
 * 가려진 인자가 있어 승인할 수 없는 줄의 안내다. 왜 막혔는지와 사용자가 할 일을 카드에서 바로 읽게 한다(ADR-088).
 */
function HiddenArgsNotice({ argsJson }: { argsJson: string | null }) {
  const keys = hiddenArgKeys(argsJson);
  return (
    <Notice variant="warning" role="status" data-testid="approval-hidden-args">
      <p data-testid="approval-hidden-args-reason" className="break-words">
        {keys.length > 0
          ? `가려진 칸(${keys.join(", ")})이 있어 승인할 수 없어요.`
          : "가려진 내용이 있어 승인할 수 없어요."}{" "}
        비밀값처럼 보이는 글은 [가림] 으로 바뀌고, 사람이 다 읽지 못한 요청은
        실행하지 않아요.
      </p>
      <p data-testid="approval-hidden-args-next" className="mt-1">
        거절한 뒤 에이전트에게 그 부분을 빼거나 풀어 쓰게 해 주세요. 가려진
        것이 지우거나 바꿀 대상의 id 라면 커넥터가 그 칸을 식별자로 선언해야
        하니 관리자에게 알려 주세요.
      </p>
    </Notice>
  );
}

/**
 * 승인 줄의 인자를 그린다.
 *
 * <p>값이 빈 인자는 이름만 남긴다. 상시 허락을 줄 수 있는 줄은 짧은 인자 둘만 위에 보이고 나머지와 빈 인자를
 * 「자세히」 로 접는다. 상시 허락을 닫은 줄은 사람이 원문을 다 읽어야 하므로 접지 않고, 빈 인자의 이름도 그 아래에
 * 그대로 보인다(ADR-065).
 */
function ApprovalArgsView({ action }: { action: ConnectorAction }) {
  const args = approvalArgs(action.argsJson);
  // 상시 허락을 줄 수 없는 줄은 인자를 모두 펼친다. 스크롤 영역 아래로 밀리거나 접힌 인자를 읽지 않고 승인하지 않게 한다.
  const fold = action.grantAllowed;
  const argsHeight = fold ? "max-h-48 overflow-y-auto" : null;
  if (args === null) {
    return action.argsJson ? (
      <p
        data-testid="approval-args"
        className={cn(
          "whitespace-pre-wrap break-all rounded-md bg-muted px-3 py-2 text-sm",
          argsHeight,
        )}
      >
        {action.argsJson}
      </p>
    ) : null;
  }
  const shown = args.rows.filter((row) => !fold || row.core);
  const folded = fold ? args.rows.filter((row) => !row.core) : [];
  const emptyLine =
    args.emptyKeys.length > 0 ? (
      <p
        data-testid="approval-args-empty"
        className="break-all text-xs text-muted-foreground"
      >
        비어 있는 항목: {args.emptyKeys.join(", ")}
      </p>
    ) : null;
  const more = folded.length + args.emptyKeys.length;

  return (
    <>
      {shown.length > 0 ? (
        <dl
          data-testid="approval-args"
          className={cn(
            "grid gap-2 rounded-md bg-muted px-3 py-2 text-sm",
            // 접을 때 위에 남는 인자는 짧은 값 둘뿐이라 스크롤 영역이 필요 없고, 넓은 화면에서는 나란히 둔다.
            folded.length > 0 ? "sm:grid-cols-2" : argsHeight,
          )}
        >
          <ArgRows rows={shown} />
        </dl>
      ) : null}
      {fold && more > 0 ? (
        <details data-testid="approval-args-more" className="text-sm">
          <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
            자세히 ({more}개)
          </summary>
          <div className="mt-2 flex flex-col gap-2">
            {folded.length > 0 ? (
              <dl
                data-testid="approval-args-folded"
                className={cn(
                  "flex flex-col gap-2 rounded-md bg-muted px-3 py-2",
                  argsHeight,
                )}
              >
                <ArgRows rows={folded} />
              </dl>
            ) : null}
            {emptyLine}
          </div>
        </details>
      ) : (
        emptyLine
      )}
    </>
  );
}

/** 「승인하고 묻지 않기」 단추와 기간 메뉴다. */
function GrantMenu({
  disabled,
  loading,
  onPick,
}: {
  disabled: boolean;
  loading: boolean;
  onPick(period: GrantPeriod): void;
}) {
  return (
    // 모달로 두면 열린 동안 메뉴 바깥을 누른 첫 클릭이 그 자리에 닿지 않는다.
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button
          size="sm"
          variant="ghost"
          data-testid="approval-grant"
          disabled={disabled}
          loading={loading}
        >
          승인하고 묻지 않기
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" aria-label="묻지 않을 기간">
        {GRANT_CHOICES.map((choice) => (
          <DropdownMenuItem
            key={choice.period}
            onSelect={() => onPick(choice.period)}
          >
            {choice.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * 에이전트가 하려는 동작 하나를 보이고 승인이나 거절을 받는다.
 *
 * <p>Control Plane 응답으로만 그린다. 인자는 남이 쓴 글이 섞일 수 있어 마크다운이나 HTML 로 읽지 않고 글자
 * 그대로 보인다. 도구의 원래 이름과 요청 번호 같은 내부 값은 그리지 않는다.
 *
 * <p>`onChanged` 에 바뀐 줄을 넘긴다. 이미 처리된 요청이면 `null` 을 넘겨 목록을 다시 읽게 한다. `grouped` 면 묶음
 * 안의 한 건이라 제목과 안내를 묶음 머리에 맡기고 테두리 없이 그린다. `locked` 면 그 줄이 든 묶음의 「모두
 * 승인」 을 보내는 중이라 단추를 막는다.
 */
export function ApprovalCard({
  action,
  onChanged,
  onDismiss,
  grouped = false,
  locked = false,
  onSendingChange,
}: {
  action: ConnectorAction;
  onChanged(next: ConnectorAction | null): void;
  onDismiss(): void;
  grouped?: boolean;
  locked?: boolean;
  /** 이 카드가 승인이나 거절을 보내기 시작하고 끝낼 때 부른다 */
  onSendingChange?(sending: boolean): void;
}) {
  const [sending, setSending] = useState<Sending>(null);
  const [error, setError] = useState<string | null>(null);
  const busy = sending !== null || locked;

  async function send(kind: Exclude<Sending, null>, grant: GrantPeriod | null) {
    if (busy) return;
    setSending(kind);
    onSendingChange?.(true);
    setError(null);
    const result =
      kind === "reject"
        ? await rejectConnectorAction(action.actionId)
        : await approveConnectorAction(action.actionId, grant);
    setSending(null);
    onSendingChange?.(false);
    if (result.ok) return onChanged(result.data);
    setError(result.message);
    // 실패해도 서버의 줄은 이미 실행 중이거나 끝났을 수 있다. 다시 읽어 지금 상태로 그린다.
    onChanged(null);
  }

  // 사람이 다 읽지 못한 인자로는 승인을 받지 않는다. 서버도 이런 줄의 승인을 실행하지 않고 끝낸다.
  const blocked = action.status === "PENDING" && action.hiddenArgs;

  return (
    <section
      data-testid="approval-card"
      data-status={action.status}
      className={cn(
        "flex min-w-0 flex-col gap-3",
        grouped
          ? "border-t border-border pt-3"
          : "rounded-lg border border-border bg-card p-4 shadow-card",
      )}
    >
      {grouped ? null : (
        <>
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
        </>
      )}
      <ApprovalArgsView action={action} />
      {blocked ? (
        <HiddenArgsNotice argsJson={action.argsJson} />
      ) : null}
      {action.status === "PENDING" ? (
        <div className="flex flex-wrap items-center gap-2">
          {blocked ? null : (
            <Button
              size="sm"
              data-testid="approval-approve"
              disabled={busy}
              loading={sending === "approve"}
              onClick={() => void send("approve", null)}
            >
              승인
            </Button>
          )}
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
          {action.grantAllowed && !blocked ? (
            <GrantMenu
              disabled={busy}
              loading={sending === "grant"}
              onPick={(period) => void send("grant", period)}
            />
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
