"use client";

import { AgentBadge, useAnswerAgent } from "@/components/agent/agent-badge";
import { cn } from "cn";
import {
  delegationStatusLabel,
  labelIdentity,
  requestedByText,
  resultArrivalText,
  type AgentLabel,
  type DelegationLine,
} from "@/lib/delegation";

/**
 * 답 본문 아래의 맡긴 줄이다. 「(이름표) 비서 → 쇼핑 담당에게 맡김 · 진행 중」. 맡긴 자리는 저장되지 않으므로 늘 본문 아래에
 * 두어 새로 고쳐도 같은 자리다(docs/features/agent-team/design.md 의 DECISION13).
 */
export function DelegationLines({ lines }: { lines: DelegationLine[] }) {
  const from = useAnswerAgent();
  if (lines.length === 0) return null;
  return (
    <ul className="mt-2 flex flex-col gap-1">
      {lines.map((line) => {
        const agent = labelIdentity(line.agent);
        return (
          <li
            key={line.executionId}
            data-testid="delegation-line"
            data-status={line.status}
            className="flex min-w-0 items-center gap-2 text-xs text-muted-foreground"
          >
            <AgentBadge agent={agent} size="sm" />
            <span className="min-w-0 truncate">
              {from.name} → {agent.name}에게 맡김
            </span>
            <span aria-hidden="true">·</span>
            <span
              data-testid="delegation-status"
              className="shrink-0 font-medium"
            >
              {delegationStatusLabel(line.status)}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** 결과 도착 줄이다. 결과마다 맡은 에이전트의 이름표와 「쇼핑 담당이 끝냈어요」 를 한 줄씩 그린다. */
export function ResultArrivalLines({ lines }: { lines: DelegationLine[] }) {
  return lines.map((line) => (
    <p
      key={line.executionId}
      data-testid="result-arrival"
      data-status={line.status}
      className="flex min-w-0 max-w-full items-center gap-2 text-sm"
    >
      <AgentBadge agent={labelIdentity(line.agent)} size="sm" />
      <span className="min-w-0 break-words">{resultArrivalText(line)}</span>
    </p>
  ));
}

/** 위임 자식처럼 대화의 에이전트가 아닌 에이전트가 요청한 승인 카드 위의 「쇼핑 담당이 요청」 줄이다. */
export function RequestedBy({
  label,
  grouped,
}: {
  label: AgentLabel;
  grouped: boolean;
}) {
  return (
    <p
      data-testid="approval-requested-by"
      className={cn(
        "flex min-w-0 items-center gap-2 text-sm text-muted-foreground",
        grouped ? null : "-mt-1",
      )}
    >
      <AgentBadge agent={labelIdentity(label)} size="sm" />
      <span className="min-w-0 break-words">{requestedByText(label)}</span>
    </p>
  );
}
