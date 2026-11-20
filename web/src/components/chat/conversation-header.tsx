"use client";

import { cn } from "cn";
import { AgentBadge, type AgentIdentity } from "@/components/agent/agent-badge";
import { Button } from "@/components/ui/button";
import { agentLabel } from "@/lib/format";

type Props = {
  answerAgent: AgentIdentity;
  agentLocked: boolean;
  agentMissing: boolean;
  /** 목록에서 찾은 대화 에이전트의 이름이다. 목록에 없으면 undefined 다 */
  agentName: string | undefined;
  /** 살펴보기 대화일 때만 값이 있다. 「지금 살펴보기」 단추를 그린다 */
  check: { sending: boolean; starting: boolean; start(): Promise<void> } | null;
};

/**
 * 메시지가 있는 대화와 대화방의 머리 줄이다. 그 대화의 이름표와 이름을 보이고 목록은 열지 않는다
 * (docs/features/app-shell/spec.md 의 REQ10). 대화의 에이전트는 바뀌지 않는다.
 */
export function ConversationHeader({
  answerAgent,
  agentLocked,
  agentMissing,
  agentName,
  check,
}: Props) {
  return (
    <div className="flex min-w-0 items-center gap-3 border-b border-border pb-3">
      <div
        className={cn(
          "flex min-w-0 flex-1 items-center gap-3 overflow-hidden",
          "text-xs text-muted-foreground",
        )}
      >
        {/* 좁은 화면의 잠긴 대화는 글자를 숨기고 이름표만 보인다. 이름은 읽기 프로그램에 남긴다(spec 의 DECISION15) */}
        <div className="flex min-w-0 items-center gap-2">
          <AgentBadge agent={answerAgent} size="sm" />
          <span className={agentLocked ? "shrink-0 max-md:hidden" : "shrink-0"}>
            에이전트
          </span>
          <span
            className={agentLocked ? "truncate max-md:sr-only" : "truncate"}
            title={agentMissing ? agentLabel(null) : agentName}
          >
            {agentMissing
              ? agentLabel(null)
              : (agentName ?? "등록된 에이전트가 없어요")}
          </span>
        </div>
      </div>
      {check ? (
        <Button
          variant="outline"
          size="sm"
          className="shrink-0"
          disabled={check.sending}
          loading={check.starting}
          loadingText="시작하는 중"
          onClick={() => void check.start()}
        >
          지금 살펴보기
        </Button>
      ) : null}
    </div>
  );
}
