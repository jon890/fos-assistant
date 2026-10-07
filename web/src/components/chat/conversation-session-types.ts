import { type ActivityPanelTarget } from "./activity/activity-panel";
import type { Turn } from "./message-bubble";
import { type useConversation } from "@/components/shell/conversations-provider";
import type { ChatEvent } from "@/lib/chat-event";
import type { AgentView } from "@/lib/agent";

export type ErrorPayload = { code: string; message: string };

/** 옆 패널에 띄운 것이다. 작업 과정이거나, 답이 만든 결과물 파일 하나다 */
export type SidePanelTarget =
  | { kind: "activity"; target: ActivityPanelTarget }
  | { kind: "artifact"; messageId: Turn["id"]; path: string; name: string };
/** 대화에 지금 도는 turn 이다. 실행 번호가 아직 붙지 않았으면 `running` 이 참이어도 나머지가 null 이다. */
export type RunningTurn = {
  running: boolean;
  executionId: number | null;
  startedAt: string | null;
};
/**
 * 도는 turn 을 보고 있는 대화와, 보기 시작할 때의 선택 판이다.
 *
 * <p>`sentHere` 는 이 창이 보낸 turn 의 스트림이 끊겨 넘어온 것인지다. 안내 문구만 이 값으로 고른다.
 */
export type ObservedTurn = {
  conversationId: string;
  version: number;
  sentHere: boolean;
};
/**
 * 끝 사건 없이 끊긴 스트림을 도는 turn 조회로 넘긴 결과다.
 *
 * <p>`history` 는 판단하면서 다시 읽은 이력이고, 읽지 못했으면 null 이다. 읽었으면 호출자가 한 번 더 읽지 않는다. `gone` 은
 * 대화가 지워졌거나 그사이 다른 대화로 옮겨 이 turn 에 할 일이 남지 않은 것이다.
 */
export type InterruptedOutcome =
  | { kind: "observing" }
  | { kind: "answered"; executionId: number | null }
  | { kind: "missing"; history: Turn[] | null }
  | { kind: "gone" };
export type InterruptedHandlers = {
  /** 답이 저장돼 있었다. 공통 정리 뒤에 호출자만 할 일을 한다. */
  onAnswered?(): void;
  /** 돌지 않고 답도 없다. 끊김 문구를 보이는 방식은 호출자가 정한다. */
  onMissing(history: Turn[] | null): Promise<void>;
};
export type TurnStreamState = {
  started: boolean;
  done: boolean;
  reportedError: boolean;
};
/**
 * 보는 중인 turn 의 사건을 대화 단위 SSE 에서 가려내는 상태다.
 *
 * <p>조각 사건에는 실행 번호가 없어 사건의 순서로 가린다. `waiting` 은 아직 `started` 를 받지 못한 것이고,
 * `skipping` 은 보는 turn 의 `started` 를 받아 그 turn 이 끝날 때까지 버리는 중이며, `passed` 는 그 turn 을 지나
 * 뒤의 사건을 받는 것이다. `watchedId` 는 보기 시작할 때의 실행 번호이고, 번호가 붙기 전이면 null 이다.
 */
export type ObservedTurnFilter = {
  watchedId: number | null;
  phase: "waiting" | "skipping" | "passed";
};
/** 대화 단위 SSE 로 받아 그리고 있는 자동 turn 이다. `pendingId` 는 흘러오는 답 조각의 임시 식별자다. */
export type AutoTurn = { state: TurnStreamState; pendingId: string };
export type TurnStreamCallbacks = {
  onSystem?(event: ChatEvent): void;
  onStarted?(event: ChatEvent): void | Promise<void>;
  onDelta?(text: string): void;
  onReset?(): void;
  onDone?(event: ChatEvent): void | Promise<void>;
  onError?(event: ChatEvent): void | Promise<void>;
};

export type ConversationSessionProps = {
  /** 이 부품이 만들어질 때의 대화다. null 이면 새 대화로 시작한 것이다. 부품이 사는 동안 바뀌지 않는다 */
  initialConversationId: string | null;
  /** 지금 대화의 식별자다. 첫 메시지나 첫 사진으로 null 에서 값이 된다 */
  conversationId: string | null;
  onConversationIdChange(id: string): void;
  agents: AgentView[];
  agentsLoading: boolean;
  agentCode: string;
  onAgentCodeChange(code: string): void;
  /** `useConversation(conversationId)` 의 결과다 */
  currentConversation: ReturnType<typeof useConversation>;
};
