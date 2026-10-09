"use client";

import { useEffect } from "react";
import { ConversationSessionView } from "./conversation-session-view";
import { commandSkillNames } from "./skill-command";
import { useStarterSuggestions } from "./use-starter-suggestions";
import { useShellTitle } from "@/components/shell/app-shell";
import { fetchCommandSkills } from "@/lib/chat-api";
import { agentLabel } from "@/lib/format";
import type { SkillListView } from "@/lib/skill";
import { ConversationSessionProps } from "./conversation-session-types";
import { useConversationSessionState } from "./use-conversation-session-state";
import { useConversationHistory } from "./use-conversation-history";
import { useConversationEvents } from "./use-conversation-events";
import { useConversationQueue } from "./use-conversation-queue";
import { useConversationSend } from "./use-conversation-send";
import { useConversationRetry } from "./use-conversation-retry";
import { useConversationControls } from "./use-conversation-controls";
import { useConversationObservation } from "./use-conversation-observation";
import { useConversationEffects } from "./use-conversation-effects";

export function ConversationSession(props: ConversationSessionProps) {
  const state = useConversationSessionState(props);
  const { conversationId, currentConversation, agents, agentCode } = props;
  const {
    freshStart,
    turns,
    sending,
    outgoing,
    skillCommands,
    setSkillCommands,
  } = state;
  const conversationHistory = useConversationHistory({ ...state, ...props });
  const conversationEvents = useConversationEvents({
    ...state,
    ...props,
    ...conversationHistory,
  });
  const conversationQueue = useConversationQueue({
    ...state,
    ...props,
    send: (attachmentIds, replacementText, attachments) =>
      send(attachmentIds, replacementText, attachments),
  });
  const conversationSend = useConversationSend({
    ...state,
    ...props,
    ...conversationHistory,
    ...conversationEvents,
    ...conversationQueue,
  });
  const { send } = conversationSend;
  const conversationRetry = useConversationRetry({
    ...state,
    ...props,
    ...conversationHistory,
    ...conversationEvents,
  });
  const conversationControls = useConversationControls({
    ...state,
    ...props,
    ...conversationHistory,
  });
  useConversationObservation({
    ...state,
    ...props,
    ...conversationHistory,
  });
  useConversationEffects({
    ...state,
    ...props,
    ...conversationEvents,
    ...conversationControls,
  });

  const agentLocked = conversationId !== null;

  // 에이전트 행이 없는 대화는 agentCode 가 null 이다. 대화 목록이 먼저 읽혀 빈 코드가 첫 에이전트로 채워져도
  // 그 에이전트의 모델과 사진 단추와 스킬이 이 대화에 보이지 않게, 상태가 아니라 이 값으로 막는다.
  const agentMissing =
    currentConversation !== undefined && currentConversation.agentCode === null;
  const selectedAgent = currentConversation
    ? agentLabel(currentConversation.agentName)
    : agents.find((agent) => agent.code === agentCode)?.name;
  useShellTitle(selectedAgent ?? null);
  const startScreen =
    freshStart && turns.length === 0 && !sending && outgoing === null;
  const currentAgent = agentMissing
    ? undefined
    : agents.find((agent) => agent.code === agentCode);
  const starters = useStarterSuggestions(
    startScreen && currentAgent ? currentAgent.code : null,
  );
  // 흐름이 붙은 에이전트는 사진을 받지 않고 커맨드도 해석하지 않는다. 사진 단추를 숨기는 기준과 같게 이 값으로 가린다.
  const commandAgentCode = currentAgent?.acceptsAttachments
    ? currentAgent.code
    : null;
  const skillNames =
    commandAgentCode !== null && skillCommands?.agentCode === commandAgentCode
      ? skillCommands.names
      : undefined;

  useEffect(() => {
    if (commandAgentCode === null) return;
    let active = true;
    fetchCommandSkills(commandAgentCode)
      .then((response) =>
        response.ok ? (response.json() as Promise<SkillListView>) : null,
      )
      .then((list) => {
        if (active && list)
          setSkillCommands({
            agentCode: commandAgentCode,
            names: commandSkillNames(list),
          });
      })
      .catch(() => {
        // 읽지 못하면 `/` 목록을 띄우지 않는다. 보내면 Control Plane 이 이름을 판별한다.
      });
    return () => {
      active = false;
    };
  }, [commandAgentCode]);

  return (
    <ConversationSessionView
      {...state}
      {...props}
      {...conversationHistory}
      {...conversationQueue}
      {...conversationSend}
      {...conversationRetry}
      {...conversationControls}
      agentLocked={agentLocked}
      agentMissing={agentMissing}
      currentAgent={currentAgent}
      startScreen={startScreen}
      skillNames={skillNames}
      starters={starters}
    />
  );
}
