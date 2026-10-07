"use client";

import { useRef, useState, useEffect, useLayoutEffect } from "react";
import { usePendingQueue } from "@/components/chat/use-pending-queue";
import { useMemoryCaptures } from "./use-memory-captures";
import { type ActivityState } from "./activity/activity-state";
import type { Turn } from "./message-bubble";
import { useConversations } from "@/components/shell/conversations-provider";
import { useShellDisplayName } from "@/components/shell/app-shell";
import {
  SidePanelTarget,
  ObservedTurn,
  ObservedTurnFilter,
  AutoTurn,
  ConversationSessionProps,
} from "./conversation-session-types";
export function useConversationSessionState({
  initialConversationId,
  conversationId,
}: ConversationSessionProps) {
  const { refresh, replace } = useConversations();
  const displayName = useShellDisplayName();
  const [turns, setTurns] = useState<Turn[]>([]);
  const conversationIdRef = useRef<string | null>(initialConversationId);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [activity, setActivity] = useState<ActivityState | null>(null);
  const [liveExpanded, setLiveExpanded] = useState(false);
  const liveExpandedRef = useRef(false);
  const [expandedOnDone, setExpandedOnDone] = useState<{
    executionId: number;
    expanded: boolean;
  } | null>(null);
  // 옆 패널은 한 번에 하나다. 작업 과정과 결과물을 한 상태에 담아 둘이 같이 열리지 않게 한다.
  const [panelTarget, setPanelTarget] = useState<SidePanelTarget | null>(null);
  const currentExecutionId = useRef<number | null>(null);
  const [executionId, setExecutionId] = useState<number | null>(null);
  const [stopRequested, setStopRequested] = useState(false);
  /**
   * 이 창의 스트림 없이 도는 turn 을 보고 있다.
   *
   * <p>보낸 창은 스트림이 이어지는 동안 자기 스트림으로 끝을 알므로 이 값을 쓰지 않는다. 대화를 열 때 도는
   * turn 이 있거나, 보낸 창의 스트림이 끝 사건 없이 끊겼는데 turn 이 아직 돌 때만 채운다.
   */
  const [observing, setObserving] = useState<ObservedTurn | null>(null);
  /** 보는 중일 때만 채운다. `observing` 을 비우는 자리에서 함께 비운다. */
  const observedTurnFilter = useRef<ObservedTurnFilter | null>(null);
  const autoTurn = useRef<AutoTurn | null>(null);
  /**
   * 이 창이 보낸 turn(보내기, 다시 생성)이 도는 동안 그 turn 의 임시 식별자를 담는다.
   *
   * <p>그동안 대화 단위 SSE 로 온 일은 `conversationTasks` 에 순서대로 보류한다. 곧바로 그리면 자동 turn 의
   * 작업 과정과 답 줄을 보낸 turn 의 끝 처리(작업 과정 비우기, 이력 다시 읽기, 입력창 풀기)가 지운다.
   */
  const sentTurnToken = useRef<string | null>(null);
  const conversationTasks = useRef<(() => Promise<void>)[]>([]);
  const drainingConversationTasks = useRef(false);
  const [selectedVersions, setSelectedVersions] = useState<
    Record<number, number>
  >({});
  const [flowIsSlow, setFlowIsSlow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [turnError, setTurnError] = useState<string | null>(null);
  /** 점검 대화의 「지금 살펴보기」 요청을 보내고 응답을 기다리는 중이다 */
  const [checkStarting, setCheckStarting] = useState(false);
  /** 「지금 살펴보기」 가 거절된 까닭이다. 다음에 누르면 지운다 */
  const [checkError, setCheckError] = useState<string | null>(null);
  /** 「결과 다시 전달」 요청을 보내고 끝나기를 기다리는 중이다. 알림 줄의 단추를 막는다 */
  const [deliveryRetrying, setDeliveryRetrying] = useState(false);
  /** 보낸 스킬 커맨드의 이름이 이 에이전트에 없었다. 입력창 아래에 알리고 다음 보내기를 시작하면 지운다 */
  const [unknownSkill, setUnknownSkill] = useState<string | null>(null);
  /** `/` 목록에 띄울 스킬 이름과 그 목록을 읽은 에이전트다. 에이전트를 바꾸면 그 에이전트의 목록을 다시 읽는다 */
  const [skillCommands, setSkillCommands] = useState<{
    agentCode: string;
    names: string[];
  } | null>(null);
  const [notFound, setNotFound] = useState(false);
  /** 입력창이 보내기를 막고 있다. 빈 대화를 만들거나 사진을 올리는 중이면 추천 질문도 막는다 */
  const [composerBlocking, setComposerBlocking] = useState(false);
  /**
   * 새 대화로 시작했는지다. 메시지가 없는 동안 새 대화 화면을 그린다.
   *
   * <p>사진을 먼저 올려 대화 번호가 생겨도 참으로 남는다. 주소로 연 대화는 메시지를 읽는 동안에도 거짓이다.
   */
  const freshStart = initialConversationId === null;
  const [messagesLoading, setMessagesLoading] = useState(
    initialConversationId !== null,
  );
  const selectionVersion = useRef(0);
  /**
   * 이 부품이 없어졌다. 그 뒤에 온 사건이 `ChatPanel` 의 대화 식별자를 바꾸지 않게 한다.
   *
   * <p>부르는 자리마다 `selectionVersion` 비교가 먼저 막는다. 그 비교를 빠뜨린 자리가 생겨도 새 대화 화면의
   * 글이 앞 대화에 저장되지 않게 한 번 더 막는 것이다.
   */
  const disposed = useRef(false);
  const pending = usePendingQueue(conversationId);
  /** 대기 줄의 취소나 보내기 요청이 도는 중이다. 그동안 대기 줄의 단추를 잠근다 */
  const [pendingBusy, setPendingBusy] = useState(false);
  /** 승인 줄을 다시 읽게 하는 값이다. `approval` 사건과 알림 줄을 받을 때 올린다 */
  const [approvalRefresh, setApprovalRefresh] = useState(0);
  // 답이 새로 저장되거나 turn 이 끝나면(`sending` 이 거짓이 되면) 그 답의 기억 기록을 다시 읽는다.
  const memoryCaptureKey = `${sending}:${turns
    .filter((turn) => turn.role === "ASSISTANT")
    .map((turn) => turn.executionId ?? "")
    .join(",")}`;
  const memoryCaptures = useMemoryCaptures(conversationId, memoryCaptureKey);
  /** 대기 메시지로 더하는 요청이 도는 중이다. 같은 글이 두 번 쌓이지 않게 그동안의 보내기를 받지 않는다 */
  const enqueueing = useRef(false);
  /** 대기 줄에 쌓인 글이 있는지다. 대화 단위 SSE 의 처리기는 연결을 열 때의 렌더에 묶여 있어 최신 값을 여기서 읽는다 */
  const hasPendingItems = useRef(false);

  useLayoutEffect(() => {
    hasPendingItems.current = pending.queue.items.length > 0;
  }, [pending.queue]);

  // 이 부품이 없어진 뒤에 온 응답과 스트림 사건을 버린다. 받던 스트림은 서버에서 계속 돌고 계속 읽힌다.
  useEffect(() => {
    // 개발 모드는 effect 를 한 번 정리하고 다시 돌린다. 그때 다시 살아 있는 것으로 둔다.
    disposed.current = false;
    return () => {
      disposed.current = true;
      selectionVersion.current += 1;
      sentTurnToken.current = null;
      conversationTasks.current = [];
      autoTurn.current = null;
    };
  }, []);
  return {
    refresh,
    replace,
    displayName,
    turns,
    setTurns,
    conversationIdRef,
    draft,
    setDraft,
    sending,
    setSending,
    activity,
    setActivity,
    liveExpanded,
    setLiveExpanded,
    liveExpandedRef,
    expandedOnDone,
    setExpandedOnDone,
    panelTarget,
    setPanelTarget,
    currentExecutionId,
    executionId,
    setExecutionId,
    stopRequested,
    setStopRequested,
    observing,
    setObserving,
    observedTurnFilter,
    autoTurn,
    sentTurnToken,
    conversationTasks,
    drainingConversationTasks,
    selectedVersions,
    setSelectedVersions,
    flowIsSlow,
    setFlowIsSlow,
    error,
    setError,
    turnError,
    setTurnError,
    checkStarting,
    setCheckStarting,
    checkError,
    setCheckError,
    deliveryRetrying,
    setDeliveryRetrying,
    unknownSkill,
    setUnknownSkill,
    skillCommands,
    setSkillCommands,
    notFound,
    setNotFound,
    composerBlocking,
    setComposerBlocking,
    freshStart,
    messagesLoading,
    setMessagesLoading,
    selectionVersion,
    disposed,
    pending,
    pendingBusy,
    setPendingBusy,
    approvalRefresh,
    setApprovalRefresh,
    memoryCaptureKey,
    memoryCaptures,
    enqueueing,
    hasPendingItems,
  };
}
export type ConversationSessionState = ReturnType<
  typeof useConversationSessionState
>;
