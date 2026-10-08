import type { AgentView } from "@/lib/agent";
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
  | "flowIsSlow"
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
  | "setDraft"
  | "conversationIdRef"
  | "refresh"
  | "executionId"
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
    | "onAgentCodeChange"
  > &
  Pick<ConversationHistory, "assignConversationId"> &
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
    startScreen: boolean;
    skillNames: string[] | undefined;
    starters: ReturnType<typeof useStarterSuggestions>;
  };
