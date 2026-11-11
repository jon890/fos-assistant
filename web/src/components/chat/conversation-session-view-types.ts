import type { AgentView } from "@/lib/agent";
import type { AgentIdentity } from "@/components/agent/agent-badge";
import type { useStarterSuggestions } from "./use-starter-suggestions";
import type { ConversationSessionProps } from "./conversation-session-types";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";
import type { ConversationQueue } from "./use-conversation-queue";
import type { ConversationSend } from "./use-conversation-send";
import type { ConversationRetry } from "./use-conversation-retry";
import type { ConversationControls } from "./use-conversation-controls";

export type Props = Pick<
  ConversationSessionState,
  | "notFound"
  | "checkStarting"
  | "checkError"
  | "messagesLoading"
  | "turns"
  | "sending"
  | "activity"
  | "liveExpanded"
  | "liveExpandedRef"
  | "setLiveExpanded"
  | "expandedOnDone"
  | "turnError"
  | "setPanelTarget"
  | "selectedVersions"
  | "setSelectedVersions"
  | "deliveryRetrying"
  | "memoryCaptures"
  | "memoryUses"
  | "displayName"
  | "error"
  | "observing"
  | "approvalRefresh"
  | "pending"
  | "pendingBusy"
  | "draft"
  | "outgoing"
  | "setOutgoing"
  | "setDraft"
  | "executionId"
  | "liveDelegations"
  | "stopRequested"
  | "setComposerBlocking"
  | "replace"
  | "unknownSkill"
  | "composerBlocking"
  | "panelTarget"
> &
  Pick<
    ConversationSessionProps,
    | "conversationId"
    | "currentConversation"
    | "agents"
    | "agentCode"
    | "agentsLoading"
    | "agentsError"
    | "onRetryAgents"
    | "onAgentCodeChange"
  > &
  Pick<ConversationHistory, "ensureConversation"> &
  Pick<
    ConversationQueue,
    "submit" | "cancelPendingMessage" | "releasePendingMessages"
  > &
  ConversationSend &
  ConversationRetry &
  ConversationControls & {
    agentLocked: boolean;
    agentMissing: boolean;
    currentAgent: AgentView | undefined;
    /** 답 줄과 대화 머리에 그릴 그 대화의 에이전트 */
    answerAgent: AgentIdentity;
    startScreen: boolean;
    skillCommandsEnabled: boolean;
    skillNames: string[] | undefined;
    starters: ReturnType<typeof useStarterSuggestions>;
  };
