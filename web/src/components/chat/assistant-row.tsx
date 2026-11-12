"use client";

import { cn } from "cn";
import {
  AgentBadge,
  AnswerAgentProvider,
  useAnswerAgent,
  type AgentIdentity,
} from "@/components/agent/agent-badge";

/** 답을 쓰는 에이전트의 이름표와 이름을 먼저 그리고, 그 아래 칸에 기다림 점이나 작업 과정이나 답을 받는다. */
export function AssistantRow({
  header,
  children,
  className,
  agent: answeredBy,
  ...props
}: React.ComponentProps<"li"> & {
  /** 이름 옆에 두는 것이다. 보낸 시각이 여기 온다 */
  header?: React.ReactNode;
  /** `@` 로 맡긴 답처럼 대화의 에이전트가 아닌 에이전트가 쓴 답이면 그 에이전트다. 안쪽의 맡긴 줄도 이 이름으로 그린다 */
  agent?: AgentIdentity;
}) {
  const conversationAgent = useAnswerAgent();
  const agent = answeredBy ?? conversationAgent;
  return (
    <AnswerAgentProvider value={agent}>
      <li
        className={cn(
          "group grid min-w-0 grid-cols-[2rem_minmax(0,1fr)] gap-2",
          className,
        )}
        {...props}
      >
        <AgentBadge agent={agent} />
        <div className="min-w-0">
          <div className="mb-1 flex min-h-8 min-w-0 items-center gap-2">
            <span
              data-testid="answer-agent-name"
              className="truncate text-sm font-medium"
            >
              {agent.name}
            </span>
            {header}
          </div>
          {children}
        </div>
      </li>
    </AnswerAgentProvider>
  );
}
