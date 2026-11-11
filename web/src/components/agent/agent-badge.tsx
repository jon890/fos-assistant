"use client";

import { createContext, useContext } from "react";
import { cn } from "cn";
import { badgeColorClass, initialOf } from "@/lib/agent-badge";

/** 이름표 하나에 그릴 에이전트다. 대화 줄과 에이전트 목록이 같은 모양으로 넘긴다 */
export type AgentIdentity = {
  /** 보이는 이름. 지운 에이전트는 「지운 에이전트」 다 */
  name: string;
  /** 자동 색을 정하는 코드. 모르면 null 이고 회색이다 */
  code: string | null;
  icon?: string | null;
  color?: string | null;
};

const SIZES = {
  sm: "size-6 text-xs",
  md: "size-8 text-sm",
} as const;

/** 아이콘이나 이름 첫 글자를 에이전트 색의 원 안에 그린다. 이름은 옆에 따로 적으므로 읽기 프로그램에는 숨긴다 */
export function AgentBadge({
  agent,
  size = "md",
  className,
}: {
  agent: AgentIdentity;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  return (
    <span
      aria-hidden="true"
      data-testid="agent-badge"
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full font-semibold",
        SIZES[size],
        badgeColorClass(agent.color, agent.code),
        className,
      )}
    >
      {agent.icon || initialOf(agent.name)}
    </span>
  );
}

/** 대화에 정해진 에이전트가 없을 때 답 줄에 그리는 이름이다 */
const DEFAULT_ANSWER_AGENT: AgentIdentity = { name: "비서", code: null };

const AnswerAgentContext = createContext<AgentIdentity>(DEFAULT_ANSWER_AGENT);

/**
 * 그 대화의 답을 쓰는 에이전트를 답 줄에 알린다. 지금은 한 대화의 답을 모두 그 대화의 에이전트가 쓴다
 * (docs/features/agent-team/design.md 의 DECISION5). 답 말풍선과 기다림 줄이 같은 값을 읽도록 props 대신 context 로 둔다.
 */
export const AnswerAgentProvider = AnswerAgentContext.Provider;

export function useAnswerAgent(): AgentIdentity {
  return useContext(AnswerAgentContext);
}
