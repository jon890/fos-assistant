"use client";

import { useEffect } from "react";
import type { AgentView } from "@/lib/agent";
import { mentionTargets } from "@/lib/mention";

/**
 * 메시지가 있는 대화의 `@` 가 고를 에이전트와 지금 고른 에이전트다(docs/features/agent-team/design.md 의 「PR4 @ 멘션」).
 *
 * <p>답이 도는 동안이나 대기 줄을 멈춰 둔 동안은 고르지 않는다. 대기 메시지는 글만 받아 대화의 에이전트에게 간다. 고른 에이전트가
 * 목록에서 사라지면(꺼지거나 지워짐) 보이지 않는 값이 남지 않게 비운다.
 */
export function useDelegateTarget({
  agents,
  conversationAgentCode,
  enabled,
  delegateTo,
  setDelegateTo,
}: {
  agents: AgentView[];
  conversationAgentCode: string;
  enabled: boolean;
  delegateTo: string | null;
  setDelegateTo(code: string | null): void;
}) {
  const delegateAgents = mentionTargets(agents, conversationAgentCode);
  const delegating = enabled && delegateAgents.length > 0;
  const delegateAgent =
    delegateTo === null
      ? undefined
      : delegateAgents.find((agent) => agent.code === delegateTo);
  const stale = delegateTo !== null && delegateAgent === undefined;
  useEffect(() => {
    if (stale) setDelegateTo(null);
  }, [stale, setDelegateTo]);
  return { delegateAgents, delegating, delegateAgent };
}
