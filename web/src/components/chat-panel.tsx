"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import { cn } from "cn";
import { describeError } from "./error-message";
import { Composer } from "./chat/composer";
import { StartScreenHeader, StarterPrompts } from "./chat/start-screen";
import { MessageList } from "./chat/message-list";
import { applyChatEvent, emptyActivity, failActivity, fromTree, type ActivityState } from "./chat/activity/activity-state";
import { ActivityPanel, type ActivityPanelTarget } from "./chat/activity/activity-panel";
import { ArtifactPanel } from "./chat/artifact/artifact-panel";
import type { Turn } from "./chat/message-bubble";
import { useConversations } from "./shell/conversations-provider";
import { escapeOnlyClosedTooltip } from "./ui/tooltip-button";
import { useShellDisplayName, useShellTitle } from "./shell/app-shell";
import { readEventStream } from "@/lib/stream";
import type { ChatEvent } from "@/lib/chat-event";
import { foldVersions } from "@/lib/message-versions";
import type { AgentView } from "@/lib/agent";
import type { ExecutionTreeResponse } from "./execution/execution-tree";

type ErrorPayload = { code: string; message: string };

/** 옆 패널에 띄운 것이다. 작업 과정이거나, 답이 만든 결과물 파일 하나다 */
type SidePanelTarget =
  | { kind: "activity"; target: ActivityPanelTarget }
  | { kind: "artifact"; messageId: Turn["id"]; path: string };
/** 대화에 지금 도는 turn 이다. 실행 번호가 아직 붙지 않았으면 `running` 이 참이어도 나머지가 null 이다. */
type RunningTurn = { running: boolean; executionId: number | null; startedAt: string | null };
/**
 * 도는 turn 을 보고 있는 대화와, 보기 시작할 때의 선택 판이다.
 *
 * <p>`sentHere` 는 이 창이 보낸 turn 의 스트림이 끊겨 넘어온 것인지다. 안내 문구만 이 값으로 고른다.
 */
type ObservedTurn = { conversationId: string; version: number; sentHere: boolean };
/**
 * 끝 사건 없이 끊긴 스트림을 도는 turn 조회로 넘긴 결과다.
 *
 * <p>`history` 는 판단하면서 다시 읽은 이력이고, 읽지 못했으면 null 이다. 읽었으면 호출자가 한 번 더 읽지 않는다. `gone` 은
 * 대화가 지워졌거나 그사이 다른 대화로 옮겨 이 turn 에 할 일이 남지 않은 것이다.
 */
type InterruptedOutcome =
  | { kind: "observing" }
  | { kind: "answered"; executionId: number | null }
  | { kind: "missing"; history: Turn[] | null }
  | { kind: "gone" };
type InterruptedHandlers = {
  /** 답이 저장돼 있었다. 공통 정리 뒤에 호출자만 할 일을 한다. */
  onAnswered?(): void;
  /** 돌지 않고 답도 없다. 끊김 문구를 보이는 방식은 호출자가 정한다. */
  onMissing(history: Turn[] | null): Promise<void>;
};
type TurnStreamState = { started: boolean; done: boolean; reportedError: boolean };
type TurnStreamCallbacks = {
  onStarted?(event: ChatEvent): void | Promise<void>;
  onDelta?(text: string): void;
  onReset?(): void;
  onDone?(event: ChatEvent): void | Promise<void>;
  onError?(event: ChatEvent): void | Promise<void>;
};
async function readPayload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

/**
 * 마지막 질문 뒤에 새로 저장된 답을 찾는다.
 *
 * <p>Control Plane 은 답 행을 성공한 turn 과, 멈춘 자리까지의 답이 있는 중지에서만 저장한다. 실패한 turn 에는
 * 질문만 남는다(`ChatService` 의 `finish` 와 `cancel`). 그래서 마지막 질문 뒤의 답 행은 끝난 답이다. 다시
 * 생성은 이전 답이 이미 그 자리에 있으므로 보내기 전에 저장돼 있던 행은 뺀다.
 */
function answerAfterLastQuestion(loaded: Turn[], savedBefore: ReadonlySet<Turn["id"]>): Turn | undefined {
  let answer: Turn | undefined;
  for (const turn of loaded) {
    if (turn.role === "USER") answer = undefined;
    else if (!savedBefore.has(turn.id)) answer = turn;
  }
  return answer;
}

/**
 * 마지막 질문이 보내기 전에 없던 새 행인지다. `started` 를 받기 전에 끊겨도 서버가 질문을 저장했을 수 있다.
 * 그때 보낸 글을 입력창에 되돌리면 저장된 질문과 함께 보이고, 다시 보내면 두 번 저장된다.
 */
function lastQuestionIsNew(loaded: Turn[], savedBefore: ReadonlySet<Turn["id"]>): boolean {
  const question = loaded.findLast((turn) => turn.role === "USER");
  return question !== undefined && !savedBefore.has(question.id);
}

/** 화면에 있는 저장된 메시지의 번호다. 끊긴 뒤 새로 저장된 답을 가려낼 때 쓴다. */
function savedIdsOf(turns: Turn[]): Set<Turn["id"]> {
  return new Set(turns.filter((turn) => typeof turn.id === "number").map((turn) => turn.id));
}

/**
 * 흐름이 오래 걸린다고 알리기까지 기다리는 시간이다.
 *
 * <p>실측한 포지션 추천 하나가 15분 넘게 걸렸고, 넷으로 나누면 더 걸릴 수도 있다. 2분이 지나면 한 번만
 * 알리고 그 뒤로는 다시 알리지 않는다.
 */
const SLOW_FLOW_MS = 120_000;

/** 다른 창에서 도는 turn 과 그 실행 나무를 다시 묻는 주기다. */
const OBSERVE_INTERVAL_MS = 3_000;

/** 도는 turn 조회가 이만큼 이어 실패하면 기다리는 표시를 거두고 이력을 다시 읽는다. */
const OBSERVE_MAX_FAILURES = 3;

export function ChatPanel({ initialConversationId }: { initialConversationId: string | null }) {
  const pathname = usePathname();
  const { conversations, refresh, newConversationVersion } = useConversations();
  const displayName = useShellDisplayName();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(initialConversationId);
  const conversationIdRef = useRef<string | null>(initialConversationId);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [activity, setActivity] = useState<ActivityState | null>(null);
  const [liveExpanded, setLiveExpanded] = useState(false);
  const liveExpandedRef = useRef(false);
  const [expandedOnDone, setExpandedOnDone] = useState<{ executionId: number; expanded: boolean } | null>(null);
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
  const [selectedVersions, setSelectedVersions] = useState<Record<number, number>>({});
  const [flowIsSlow, setFlowIsSlow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [turnError, setTurnError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [agents, setAgents] = useState<AgentView[]>([]);
  const [agentsLoading, setAgentsLoading] = useState(true);
  const [agentCode, setAgentCode] = useState<string>("");
  /** 입력창이 보내기를 막고 있다. 빈 대화를 만들거나 사진을 올리는 중이면 추천 질문도 막는다 */
  const [composerBlocking, setComposerBlocking] = useState(false);
  /**
   * 새 대화로 시작했는지다. 메시지가 없는 동안 새 대화 화면을 그린다.
   *
   * <p>사진을 먼저 올려 대화 번호가 생겨도 참으로 남는다. 주소로 연 대화는 메시지를 읽는 동안에도 거짓이다.
   */
  const [freshStart, setFreshStart] = useState(initialConversationId === null);
  const [messagesLoading, setMessagesLoading] = useState(initialConversationId !== null);
  const selectionVersion = useRef(0);
  const previousPathname = useRef(pathname);
  const previousNewVersion = useRef(newConversationVersion);
  /**
   * 사용자가 대화를 전환할 때만 올린다. `Composer` 의 `key` 로 써서 그때만 다시 만든다.
   *
   * <p>`conversationId` 를 그대로 key 로 쓰면 안 된다. 새 대화에서 첫 사진을 올릴 때 Composer 가 빈
   * 대화를 만들어 `conversationId` 가 null 에서 공개 식별자로 바뀌는데, 그 순간 Composer 가 다시 만들어져
   * 올리는 중인 사진이 사라진다.
   */
  const [composerGeneration, setComposerGeneration] = useState(0);

  useEffect(() => {
    fetch("/api/agents")
      .then((response) => (response.ok ? response.json() : []))
      .then((data: AgentView[]) => {
        setAgents(data);
        setAgentCode((current) => current || data[0]?.code || "");
      })
      .catch(() => setAgents([]))
      .finally(() => setAgentsLoading(false));
  }, []);

  useEffect(() => {
    if (initialConversationId === null) return;
    const version = ++selectionVersion.current;
    conversationIdRef.current = initialConversationId;
    setConversationId(initialConversationId);
    setFreshStart(false);
    setMessagesLoading(true);
    setNotFound(false);
    setFlowIsSlow(false);
    setError(null);
    setTurnError(null);
    setTurns([]);
    setSending(false);
    setObserving(null);
    setActivity(null);
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    setPanelTarget(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setSelectedVersions({});
    setComposerGeneration((generation) => generation + 1);
    void (async () => {
      try {
        // 도는 turn 을 이력보다 먼저 묻는다. 거꾸로 하면 두 호출 사이에 turn 이 끝났을 때 답이 빠진
        // 이력과 `running=false` 를 함께 받아, 새로 고칠 때까지 답이 보이지 않는다.
        let running: RunningTurn | null = null;
        try {
          const runningResponse = await fetch(`/api/chat/conversations/${initialConversationId}/running`,
            { cache: "no-store" });
          if (runningResponse.ok) {
            running = await readPayload<RunningTurn>(runningResponse);
          } else if (runningResponse.status === 404) {
            const payload = await readPayload<ErrorPayload>(runningResponse).catch(() => null);
            if (payload?.code === "CONVERSATION_NOT_FOUND") {
              if (selectionVersion.current === version) setNotFound(true);
              return;
            }
          }
        } catch {
          // 묻지 못하면 돌지 않는 것으로 보고 이력을 읽는다. 이력 읽기가 실패하면 그 오류가 뜬다.
        }
        if (selectionVersion.current !== version) return;
        if (running?.running) beginObserving(initialConversationId, version, running);
        const response = await fetch(`/api/chat/conversations/${initialConversationId}/messages`);
        if (!response.ok) {
          const payload = await readPayload<ErrorPayload>(response);
          if (payload.code === "CONVERSATION_NOT_FOUND") {
            if (selectionVersion.current === version) setNotFound(true);
            return;
          }
          throw new Error(describeError(payload.code, payload.message));
        }
        const messages = await readPayload<Turn[]>(response);
        if (selectionVersion.current === version) setTurns(messages);
      } catch (reason) {
        if (selectionVersion.current === version) {
          setError(reason instanceof Error ? reason.message : "대화 이력을 읽지 못했다.");
        }
      } finally {
        if (selectionVersion.current === version) {
          setMessagesLoading(false);
        }
      }
    })();
  }, [initialConversationId]);

  /**
   * 다른 창에서 도는 turn 을 주기마다 다시 묻고 작업 과정을 다시 그린다.
   *
   * <p>창이 가려진 동안은 쉬고, 보이게 되면 곧바로 한 번 묻는다. 대화를 옮기거나 화면이 사라지면 정리 함수가
   * 타이머를 지우고, 그 뒤에 온 응답은 선택 판으로 버린다.
   */
  useEffect(() => {
    if (observing === null) return;
    const { conversationId: id, version } = observing;
    let active = true;
    let inFlight = false;
    let failures = 0;
    let timer: number | undefined;
    const current = () => active && selectionVersion.current === version;

    const loadTree = async (treeExecutionId: number) => {
      try {
        const response = await fetch(`/api/usage/executions/${treeExecutionId}/tree`, { cache: "no-store" });
        if (!response.ok) return;
        const tree = await readPayload<ExecutionTreeResponse>(response);
        if (!current() || currentExecutionId.current !== treeExecutionId) return;
        const items = fromTree(tree, { running: true });
        setActivity((previous) => previous && { ...previous, items });
      } catch {
        // 작업 과정은 다음 주기에 다시 읽는다. 기다리는 표시는 그대로 둔다.
      }
    };

    const finish = async () => {
      // 이력을 먼저 읽어 끝난 답의 실행 번호로 작업 과정 패널을 저장된 나무로 바꾼다.
      let loaded: Turn[] | null = null;
      try {
        loaded = await refreshMessages(id, version);
      } catch (reason) {
        if (selectionVersion.current === version) {
          setError(reason instanceof Error ? reason.message : "대화 이력을 읽지 못했다.");
        }
      }
      if (selectionVersion.current !== version) return;
      releaseObserving(loaded ? answerAfterLastQuestion(loaded, new Set())?.executionId ?? null : null);
      void refresh();
    };

    /** 한 번 묻고, 다음 주기에도 물을지를 돌려준다. */
    const pollOnce = async (): Promise<boolean> => {
      let running: RunningTurn | null = null;
      try {
        const response = await fetch(`/api/chat/conversations/${id}/running`, { cache: "no-store" });
        if (response.ok) {
          running = await readPayload<RunningTurn>(response);
        } else if (response.status === 404) {
          const payload = await readPayload<ErrorPayload>(response).catch(() => null);
          if (payload?.code === "CONVERSATION_NOT_FOUND") {
            // 보는 동안 대화가 지워졌다. 되풀이해 물어도 돌아오지 않으므로 곧바로 멈추고 첫 조회와 같은 화면으로 간다.
            if (!current()) return false;
            releaseObserving();
            setNotFound(true);
            return false;
          }
        }
      } catch {
        // 아래에서 실패로 센다.
      }
      if (!current()) return false;
      if (running === null) {
        failures += 1;
        if (failures < OBSERVE_MAX_FAILURES) return true;
        await finish();
        return false;
      }
      failures = 0;
      if (!running.running) {
        await finish();
        return false;
      }
      if (running.executionId === null) {
        // 번호가 아직 붙지 않았다. 중지 단추를 잠가 두고 다음 조회에서 번호를 받는다.
        currentExecutionId.current = null;
        setExecutionId(null);
        return true;
      }
      if (running.executionId !== currentExecutionId.current) {
        // 처음 번호를 받았거나 provider 를 넘어가 번호가 바뀌었다. 중지도 새 번호로 보낸다.
        currentExecutionId.current = running.executionId;
        setExecutionId(running.executionId);
        const startedAt = running.startedAt ? Date.parse(running.startedAt) : Date.now();
        setActivity((previous) => previous && { ...previous, startedAt });
      }
      await loadTree(running.executionId);
      return current();
    };

    const schedule = () => {
      if (!active || document.hidden) return;
      timer = window.setTimeout(() => {
        timer = undefined;
        void run(pollOnce);
      }, OBSERVE_INTERVAL_MS);
    };

    const run = async (step: () => Promise<boolean>) => {
      inFlight = true;
      let keepGoing = false;
      try {
        keepGoing = await step();
      } finally {
        inFlight = false;
      }
      if (keepGoing) schedule();
    };

    const onVisibilityChange = () => {
      window.clearTimeout(timer);
      timer = undefined;
      if (!document.hidden && !inFlight) void run(pollOnce);
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    void run(async () => {
      // 대화를 열 때 이미 도는 turn 을 물었다. 번호가 있으면 작업 과정만 곧바로 읽는다.
      if (currentExecutionId.current !== null) await loadTree(currentExecutionId.current);
      return current();
    });
    return () => {
      active = false;
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [observing]);

  useEffect(() => {
    const selected = conversations.find((item) => item.id === conversationId);
    if (selected) setAgentCode(selected.agentCode);
  }, [conversations, conversationId]);

  useEffect(() => {
    const previous = previousPathname.current;
    previousPathname.current = pathname;
    if (previous !== "/" && pathname === "/" && conversationIdRef.current !== null) {
      startNewConversation();
    }
  }, [pathname]);

  useEffect(() => {
    if (newConversationVersion !== previousNewVersion.current) {
      previousNewVersion.current = newConversationVersion;
      startNewConversation();
    }
  }, [newConversationVersion]);

  useEffect(() => {
    const onEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.isComposing) return;
      // 「중지」 의 풀이만 닫은 Esc 는 막혀 있어도 받는다. 마우스를 올려 둔 채 누른 첫 Esc 가 답을 멈춰야 한다.
      if (event.defaultPrevented && !escapeOnlyClosedTooltip(event)) return;
      if (panelTarget) {
        event.preventDefault();
        setPanelTarget(null);
        return;
      }
      if (sending && currentExecutionId.current !== null && !stopRequested) {
        event.preventDefault();
        void stop();
      }
    };
    window.addEventListener("keydown", onEscape);
    return () => window.removeEventListener("keydown", onEscape);
  }, [panelTarget, sending, stopRequested]);

  /**
   * 흐름이 시작되고 2분이 지나면 한 번 알린다.
   *
   * <p>첫 단계 사건이 올 때 재기 시작한다. 그전에는 이 turn 이 흐름인지 알 수 없다.
   */
  const flowActive = activity?.items.some((item) => item.kind === "step") ?? false;

  useEffect(() => {
    if (!flowActive || flowIsSlow) return;
    const timer = setTimeout(() => setFlowIsSlow(true), SLOW_FLOW_MS);
    return () => clearTimeout(timer);
  }, [flowActive, flowIsSlow]);

  const agentLocked = conversationId !== null;

  function startNewConversation() {
    selectionVersion.current += 1;
    conversationIdRef.current = null;
    setObserving(null);
    setComposerGeneration((generation) => generation + 1);
    setConversationId(null);
    setFreshStart(true);
    setAgentCode(agents[0]?.code ?? "");
    setTurns([]);
    setActivity(null);
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    setPanelTarget(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setSelectedVersions({});
    setFlowIsSlow(false);
    setError(null);
    setTurnError(null);
    setNotFound(false);
    setDraft("");
    setSending(false);
    setMessagesLoading(false);
  }

  /**
   * 도는 turn 을 보기 시작한다. 보낸 창과 같은 상태를 채워 기다리는 표시와 중지가 같이 동작한다.
   *
   * <p>스트림이 끊겨 넘어온 창은 이미 받은 작업 과정을 그대로 두고, 다음 나무 조회가 그것을 덮는다.
   */
  function beginObserving(id: string, version: number, running: RunningTurn, sentHere = false) {
    const startedAt = running.startedAt ? Date.parse(running.startedAt) : Date.now();
    setSending(true);
    setStopRequested(false);
    currentExecutionId.current = running.executionId;
    setExecutionId(running.executionId);
    setActivity((previous) => ({ ...emptyActivity(startedAt), items: previous?.items ?? [] }));
    setObserving({ conversationId: id, version, sentHere });
  }

  /**
   * 보낸 창의 스트림이 `done`, `stopped`, `error` 없이 끝났을 때 도는 turn 을 묻는다.
   *
   * <p>스트림이 끊겨도 실행은 계속될 수 있다. 실제로 끊긴 뒤 13분을 더 돌아 성공했는데 화면은 그동안 실패로
   * 보였다. 돌고 있으면 보는 창으로 넘어가고, 돌지 않으면 이력을 다시 읽어 답이 저장됐는지 본다.
   *
   * <p>도는 turn 을 이력보다 먼저 묻는다. 대화를 열 때와 같은 까닭이다. 넘어갈 때 받던 답 조각은 치운다.
   * 저장된 답이 아니고, 남으면 기다리는 표시 대신 멈춘 답으로 보인다. 이력을 다시 읽으면 임시 질문도 저장된
   * 질문으로 바뀌고, 보기가 끝나면 이력을 한 번 더 읽어 저장된 것만 남는다.
   *
   * @param savedBefore 보내기 전에 화면에 있던 저장된 메시지의 번호다. 그 밖의 답이 마지막 질문 뒤에 있으면 답이 저장된 것이다.
   * @param streamedId 받던 답 조각의 임시 식별자다.
   */
  async function handOffInterruptedStream(version: number, savedBefore: ReadonlySet<Turn["id"]>, streamedId: string)
    : Promise<InterruptedOutcome> {
    const id = conversationIdRef.current;
    if (id === null) return { kind: "missing", history: null };
    let running: RunningTurn | null = null;
    try {
      const response = await fetch(`/api/chat/conversations/${id}/running`, { cache: "no-store" });
      if (response.ok) {
        running = await readPayload<RunningTurn>(response);
      } else if (response.status === 404) {
        const payload = await readPayload<ErrorPayload>(response).catch(() => null);
        if (payload?.code === "CONVERSATION_NOT_FOUND") {
          // 대화를 열 때와 보는 중과 같게 대화를 찾을 수 없다는 화면으로 간다.
          if (selectionVersion.current === version) setNotFound(true);
          return { kind: "gone" };
        }
      }
    } catch {
      // 묻지 못하면 돌지 않는 것으로 보고 이력으로 판단한다.
    }
    if (selectionVersion.current !== version) return { kind: "gone" };
    if (running?.running) {
      setTurns((previous) => previous.filter((turn) => turn.id !== streamedId));
      await refreshMessages(id, version).catch(() => {
        // 이력을 못 읽으면 임시 질문을 둔 채 본다. 보기가 끝날 때 이력을 다시 읽는다.
      });
      if (selectionVersion.current !== version) return { kind: "gone" };
      beginObserving(id, version, running, true);
      return { kind: "observing" };
    }
    let loaded: Turn[];
    try {
      loaded = await refreshMessages(id, version);
    } catch {
      return { kind: "missing", history: null };
    }
    if (selectionVersion.current !== version) return { kind: "gone" };
    const answer = answerAfterLastQuestion(loaded, savedBefore);
    return answer ? { kind: "answered", executionId: answer.executionId ?? null } : { kind: "missing", history: loaded };
  }

  /**
   * 끊긴 스트림의 결과를 보내기와 다시 생성이 같은 방식으로 마무리한다. 넘어가면 보는 창이 입력창을 풀고, 답이
   * 저장돼 있으면 끝 사건을 받았을 때처럼 정리하며, 답이 없을 때만 호출자에게 넘긴다.
   */
  async function settleInterruptedStream(
    version: number,
    savedBefore: ReadonlySet<Turn["id"]>,
    streamedId: string,
    handlers: InterruptedHandlers,
  ): Promise<InterruptedOutcome["kind"]> {
    const outcome = await handOffInterruptedStream(version, savedBefore, streamedId);
    if (outcome.kind === "answered") {
      setActivity(null);
      setFlowIsSlow(false);
      settleFinishedActivity(outcome.executionId);
      void refresh();
      handlers.onAnswered?.();
    } else if (outcome.kind === "missing") {
      await handlers.onMissing(outcome.history);
    }
    return outcome.kind;
  }

  /**
   * 끝난 turn 의 작업 과정을 저장된 답으로 넘긴다. 펼쳐 둔 상태를 저장된 블록이 이어받고, live 패널은 저장된
   * 나무로 바뀐다. 답의 실행 번호가 없으면 보일 것이 없어 live 패널을 닫는다.
   */
  function settleFinishedActivity(finishedExecutionId: number | null) {
    if (finishedExecutionId !== null) {
      setExpandedOnDone({ executionId: finishedExecutionId, expanded: liveExpandedRef.current });
    }
    // 결과물 패널은 turn 이 끝나도 그대로 둔다.
    setPanelTarget((previous) => {
      if (previous?.kind !== "activity" || previous.target.mode !== "live") return previous;
      return finishedExecutionId !== null
        ? { kind: "activity", target: { mode: "saved", executionId: finishedExecutionId } }
        : null;
    });
  }

  /**
   * 보는 상태를 모두 되돌린다. 끝났을 때와 조회가 이어 실패했을 때 쓴다.
   *
   * @param finishedExecutionId 끝난 답의 실행 번호다. 없으면 live 작업 과정 패널을 닫는다.
   */
  function releaseObserving(finishedExecutionId: number | null = null) {
    settleFinishedActivity(finishedExecutionId);
    setSending(false);
    setObserving(null);
    setActivity(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
  }

  async function refreshMessages(id: string, version: number): Promise<Turn[]> {
    const response = await fetch(`/api/chat/conversations/${id}/messages`);
    if (!response.ok) {
      const payload = await readPayload<ErrorPayload>(response);
      throw new Error(describeError(payload.code, payload.message));
    }
    const loaded = await readPayload<Turn[]>(response);
    if (selectionVersion.current === version) setTurns(loaded);
    return loaded;
  }

  function latestSlots() {
    const saved = turns.filter((turn): turn is Turn & { id: number } => typeof turn.id === "number")
      .map((turn) => ({ ...turn, replacesMessageId: turn.replacesMessageId ?? null }));
    return foldVersions(saved, {}).at(-1);
  }

  function clearSelectedSlot(slotId: number | undefined) {
    if (slotId === undefined) return;
    setSelectedVersions((previous) => {
      const next = { ...previous };
      delete next[slotId];
      return next;
    });
  }

  /** 모든 turn 요청이 같은 사건과 실행 상태를 처리한다. 메시지 저장 방식만 호출자가 정한다. */
  async function consumeTurnStream(
    response: Response,
    version: number,
    state: TurnStreamState,
    callbacks: TurnStreamCallbacks,
  ) {
    await readEventStream<ChatEvent>(response, async (event) => {
      if (selectionVersion.current !== version) return;
      if (event.type === "started") {
        state.started = true;
        currentExecutionId.current = event.executionId ?? null;
        setExecutionId(event.executionId ?? null);
        await callbacks.onStarted?.(event);
      } else if (event.type === "delta" && event.text) {
        callbacks.onDelta?.(event.text);
      } else if (event.type === "reset") {
        callbacks.onReset?.();
        setActivity((previous) => previous && applyChatEvent(previous, event));
      } else if (["tool", "subagent", "step", "switched"].includes(event.type)) {
        setActivity((previous) => previous && applyChatEvent(previous, event));
      } else if ((event.type === "done" || event.type === "stopped") && event.conversationId) {
        state.done = true;
        setActivity((previous) => previous && applyChatEvent(previous, event));
        const finishedExecutionId = event.executionId ?? currentExecutionId.current;
        settleFinishedActivity(finishedExecutionId);
        await callbacks.onDone?.(event);
        setActivity(null);
        setFlowIsSlow(false);
      } else if (event.type === "error") {
        state.reportedError = true;
        setActivity((previous) => previous && failActivity(previous, Date.now()));
        setLiveExpanded(false);
        liveExpandedRef.current = false;
        setFlowIsSlow(false);
        await callbacks.onError?.(event);
      }
    });
  }

  /** 전송이 실제로 끝났는지를 돌려준다. `Composer` 는 이 값을 보고 실패했을 때 미리보기를 남긴다 */
  async function send(attachmentIds: number[], replacementText?: string): Promise<boolean> {
    const text = (replacementText ?? draft).trim();
    if (text.length === 0 || sending || (conversationId === null && agentCode.length === 0)) return false;

    const version = selectionVersion.current;
    const pendingId = `pending-${Date.now()}`;
    const assistantPendingId = `assistant-${Date.now()}`;
    const savedBefore = savedIdsOf(turns);
    /** 스트림이 끊겨 보는 창으로 넘어갔다. 보기가 입력창을 풀므로 끝낼 때 풀지 않는다. */
    let handedOff = false;
    setSending(true);
    setError(null);
    setTurnError(null);
    // 글을 인자로 받았으면 입력창의 글과 무관하게 보낸다. 추천 질문이 그렇다.
    if (replacementText === undefined) setDraft("");
    setActivity(emptyActivity(Date.now()));
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
    const finishFailedActivity = () => {
      if (selectionVersion.current !== version) return;
      setActivity((previous) => previous && failActivity(previous, Date.now()));
      setLiveExpanded(false);
      liveExpandedRef.current = false;
      setFlowIsSlow(false);
    };
    setTurns((previous) => [
      ...previous,
      { id: pendingId, role: "USER", content: text, senderName: null },
    ]);

    const restoreFailedMessage = () => {
      if (selectionVersion.current !== version) return;
      if (replacementText === undefined) setDraft(text);
      setTurns((previous) =>
        previous.filter((turn) => turn.id !== pendingId && turn.id !== assistantPendingId),
      );
    };
    const refreshAfterStartedFailure = async (): Promise<boolean> => {
      if (conversationIdRef.current === null) return false;
      try {
        await refreshMessages(conversationIdRef.current, version);
        return true;
      } catch {
        // `started` 뒤에는 서버에 질문이 남는다. 다만 새 이력을 못 받았을 때 임시 질문을 저장된 판처럼
        // 보이면 다음 다시 시도가 무엇을 대상으로 하는지 알 수 없으므로 화면에서 치운다.
        setTurns((previous) => previous.filter((turn) => turn.id !== pendingId && turn.id !== assistantPendingId));
        return false;
      }
    };
    const stream: TurnStreamState = { started: false, done: false, reportedError: false };
    const startedFailureMessage = (message: string, refreshed: boolean) => refreshed
      ? message
      : `${message} 대화 이력을 다시 읽지 못했다. 아래에서 다시 시도하거나 대화를 새로고침해 주세요.`;

    /** 끝 사건 없이 끊긴 스트림을 마무리한다. 돌고 있거나 답이 저장됐으면 오류로 끝내지 않는다. */
    const finishInterrupted = async (): Promise<boolean> => {
      // 질문이 이미 저장돼 글을 되돌리지 않았으면 전송이 끝난 것으로 알린다. 입력창이 첨부 미리보기를 남기지 않게 한다.
      let questionKept = false;
      const kind = await settleInterruptedStream(version, savedBefore, assistantPendingId, {
        onMissing: async (history) => {
          finishFailedActivity();
          const message = describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다.");
          const questionSaved = stream.started || (history !== null && lastQuestionIsNew(history, savedBefore));
          if (questionSaved && conversationIdRef.current !== null) {
            questionKept = true;
            const refreshed = history !== null || await refreshAfterStartedFailure();
            if (selectionVersion.current === version) setTurnError(startedFailureMessage(message, refreshed));
          } else {
            restoreFailedMessage();
            if (selectionVersion.current === version) setError(message);
          }
        },
      });
      if (kind === "observing") handedOff = true;
      return kind === "observing" || kind === "answered" || stream.started || questionKept;
    };

    const requestBody = {
      conversationId,
      text,
      agentCode,
      attachmentIds,
    };

    const sendWithoutStream = async () => {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestBody),
      });
      const payload = await readPayload<
        ErrorPayload & { conversationId: string; assistantText: string }
      >(response);
      if (!response.ok) {
        restoreFailedMessage();
        setError(describeError(payload.code, payload.message));
        return false;
      }
      if (selectionVersion.current !== version) return true;
      if (conversationIdRef.current === null) {
        window.history.replaceState(null, "", `/chat/${payload.conversationId}`);
      }
      conversationIdRef.current = payload.conversationId;
      setConversationId(payload.conversationId);
      setTurns((previous) => [
        ...previous,
        {
          id: `assistant-${Date.now()}`,
          role: "ASSISTANT",
          content: payload.assistantText,
          senderName: null,
        },
      ]);
      await Promise.all([refresh(), refreshMessages(payload.conversationId, version)]);
      return true;
    };

    try {
      let response: Response;
      try {
        response = await fetch("/api/chat/stream", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(requestBody),
        });
      } catch {
        return await sendWithoutStream();
      }

      if (!response.ok) {
        if ([404, 405, 415, 501].includes(response.status)) {
          return await sendWithoutStream();
        }
        const payload = await readPayload<ErrorPayload>(response);
        restoreFailedMessage();
        setError(describeError(payload.code, payload.message));
        return false;
      }

      try {
        await consumeTurnStream(response, version, stream, {
          onStarted: (event) => {
            if (!event.conversationId) return;
            if (conversationIdRef.current === null) window.history.replaceState(null, "", `/chat/${event.conversationId}`);
            conversationIdRef.current = event.conversationId;
            setConversationId(event.conversationId);
            void refresh();
          },
          onDelta: (textDelta) => {
            setTurns((previous) => {
              const current = previous.find((turn) => turn.id === assistantPendingId);
              if (!current) return [...previous,
                { id: assistantPendingId, role: "ASSISTANT", content: textDelta, senderName: null }];
              return previous.map((turn) =>
                turn.id === assistantPendingId ? { ...turn, content: turn.content + textDelta } : turn,
              );
            });
          },
          onReset: () => {
            // 막혀서 넘어간 시도의 조각이다. 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
            setTurns((previous) => previous.filter((turn) => turn.id !== assistantPendingId));
          },
          onDone: async (event) => {
            conversationIdRef.current = event.conversationId!;
            setConversationId(event.conversationId!);
            await Promise.all([
              refresh(),
              refreshMessages(event.conversationId!, version),
            ]);
          },
          onError: async (event) => {
            const message = describeError(event.code ?? "INTERNAL_ERROR", event.message ?? "요청을 처리하지 못했다.");
            if (stream.started && conversationIdRef.current !== null) {
              const refreshed = await refreshAfterStartedFailure();
              setTurnError(startedFailureMessage(message, refreshed));
            } else {
              restoreFailedMessage();
              setError(message);
            }
          },
        });
      } catch {
        if (!stream.done && !stream.reportedError) return await finishInterrupted();
        return stream.started || stream.done;
      }
      if (!stream.done && !stream.reportedError) return await finishInterrupted();
      return stream.started || stream.done;
    } catch (reason) {
      finishFailedActivity();
      restoreFailedMessage();
      setError(reason instanceof Error ? reason.message : "요청을 보내지 못했다.");
      return false;
    } finally {
      if (selectionVersion.current === version && !handedOff) setSending(false);
    }
  }

  async function regenerate() {
    if (conversationId === null || sending) return;
    const version = selectionVersion.current;
    const regeneratedSlotId = latestSlots()?.answers.at(-1)?.version.slotId;
    const pendingId = `assistant-regenerate-${Date.now()}`;
    const stream = { started: false, done: false, reportedError: false };
    const savedBefore = savedIdsOf(turns);
    /** 스트림이 끊겨 보는 창으로 넘어갔다. 보기가 입력창을 풀므로 끝낼 때 풀지 않는다. */
    let handedOff = false;
    /** 끊긴 turn 을 판단하며 이력을 이미 다시 읽었다. 실패 처리에서 한 번 더 읽지 않는다. */
    let historyRead = false;
    setSending(true);
    setError(null);
    setTurnError(null);
    setActivity(emptyActivity(Date.now()));
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
    try {
      const response = await fetch(`/api/chat/conversations/${conversationId}/regenerate`, { method: "POST" });
      if (!response.ok) {
        const payload = await readPayload<ErrorPayload>(response);
        setError(describeError(payload.code, payload.message));
        if (payload.code === "MESSAGE_NOT_LATEST") await refreshMessages(conversationId, version);
        return;
      }
      try {
        await consumeTurnStream(response, version, stream, {
          onDelta: (textDelta) => {
            setTurns((previous) => {
              const current = previous.find((turn) => turn.id === pendingId);
              return current ? previous.map((turn) => turn.id === pendingId ? { ...turn, content: turn.content + textDelta } : turn)
                : [...previous, { id: pendingId, role: "ASSISTANT", content: textDelta, senderName: null }];
            });
          },
          onReset: () => {
            setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
          },
          onError: async (event) => {
            setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
            setTurnError(describeError(event.code ?? "INTERNAL_ERROR", event.message ?? "요청을 처리하지 못했다."));
            if (event.code === "MESSAGE_NOT_LATEST") await refreshMessages(conversationId, version);
          },
          onDone: async (event) => {
            setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
            await Promise.all([refresh(), refreshMessages(event.conversationId!, version)]);
            clearSelectedSlot(regeneratedSlotId);
          },
        });
      } catch (reason) {
        // 끝 사건을 받은 뒤의 실패는 그대로 올린다. 받기 전에 읽기가 깨졌으면 끊긴 것으로 보고 아래에서 묻는다.
        if (stream.done || stream.reportedError) throw reason;
      }
      if (!stream.done && !stream.reportedError) {
        const kind = await settleInterruptedStream(version, savedBefore, pendingId, {
          onAnswered: () => clearSelectedSlot(regeneratedSlotId),
          onMissing: async (history) => {
            historyRead = history !== null;
          },
        });
        if (kind === "observing") handedOff = true;
        if (kind !== "missing") return;
        throw new Error(describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다."));
      }
    } catch (reason) {
      if (selectionVersion.current === version) {
        setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
        setActivity((previous) => previous && failActivity(previous, Date.now()));
        setTurnError(reason instanceof Error ? reason.message : "다시 생성하지 못했다.");
        if (!historyRead) await refreshMessages(conversationId, version).catch(() => {});
      }
    } finally {
      if (selectionVersion.current === version && !handedOff) {
        setSending(false);
        setFlowIsSlow(false);
      }
    }
  }

  async function stop() {
    const executionId = currentExecutionId.current;
    if (executionId === null || stopRequested) return;
    setStopRequested(true);
    try {
      const response = await fetch(`/api/chat/executions/${executionId}/stop`, { method: "POST" });
      if (response.status === 202) return;
      const payload = await readPayload<ErrorPayload>(response);
      if (payload.code === "EXECUTION_NOT_RUNNING") return;
      setStopRequested(false);
      setError(describeError(payload.code, payload.message));
    } catch {
      setStopRequested(false);
      setError(describeError("HERMES_UNAVAILABLE", "중지 요청을 보내지 못했다."));
    }
  }

  const selectedAgent = conversations.find((item) => item.id === conversationId)?.agentName
    ?? agents.find((agent) => agent.code === agentCode)?.name;
  useShellTitle(selectedAgent ?? null);
  const startScreen = freshStart && turns.length === 0 && !sending;
  const currentAgent = agents.find((agent) => agent.code === agentCode);

  if (notFound) {
    return (
      <section data-testid="conversation-not-found" className="mx-auto max-w-3xl py-12 text-center">
        <h1 className="text-lg font-semibold">대화를 찾을 수 없다</h1>
        <Link href="/" className="mt-4 inline-block rounded-md bg-muted px-3 py-2 text-sm">새 대화</Link>
      </section>
    );
  }

  // 입력창은 첫 메시지 전후로 같은 자리에 하나만 둔다. 앞뒤 형제만 조건부로 그리고, 가운데에서 아래로
  // 옮기는 것은 감싸는 요소의 클래스로만 한다. 부모가 바뀌면 입력창이 새로 만들어져 올린 사진이 지워진다.
  return (
    <section className="relative flex h-full min-h-0 min-w-0">
      {startScreen ? null : <h1 className="sr-only">대화</h1>}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {startScreen ? null : (
          <div className="flex min-w-0 items-center gap-3 border-b border-border pb-3">
            <div className={cn(
              "flex min-w-0 flex-1 items-center gap-3 overflow-hidden",
              "text-xs text-muted-foreground",
            )}>
              <div className={`min-w-0 items-center gap-2 ${agentLocked ? "hidden md:flex" : "flex"}`}>
                <span className="shrink-0">에이전트</span>
                <span className="truncate" title={currentAgent?.name}>
                  {currentAgent?.name ?? "등록된 에이전트 없음"}
                </span>
              </div>
            </div>
          </div>
        )}

        {startScreen ? null : <MessageList
          turns={turns}
          loading={messagesLoading}
          sending={sending}
          activity={activity}
          conversationId={conversationId}
          flowIsSlow={flowIsSlow}
          liveExpanded={liveExpanded}
          onLiveExpandedChange={(value) => { liveExpandedRef.current = value; setLiveExpanded(value); }}
          expandedOnDone={expandedOnDone}
          turnError={turnError}
          onOpenSaved={(executionId) => setPanelTarget({ kind: "activity", target: { mode: "saved", executionId } })}
          onOpenLive={() => {
            if (activity) setPanelTarget({ kind: "activity", target: { mode: "live", state: activity } });
          }}
          onOpenArtifact={(messageId, path) => setPanelTarget({ kind: "artifact", messageId, path })}
          selectedVersions={selectedVersions}
          onVersionChange={(slotId, index) => setSelectedVersions((previous) => ({ ...previous, [slotId]: index }))}
          onRegenerate={() => { void regenerate(); }}
          onRetry={() => { void regenerate(); }}
          onAnswer={(text) => { void send([], text); }}
        />}

        <div className={startScreen ? "flex min-h-0 flex-1 flex-col overflow-y-auto" : "shrink-0"}>
          {startScreen ? (
            <StartScreenHeader
              displayName={displayName}
              agents={agents}
              loading={agentsLoading}
              selectedCode={agentCode}
              onSelect={setAgentCode}
              locked={agentLocked}
            />
          ) : null}
          {error ? <p className="mb-2 rounded-md bg-muted px-3 py-2 text-sm">{error}</p> : null}
          {observing ? (
            <p data-testid="observing-notice" className="mb-2 text-xs text-muted-foreground">
              {observing.sentHere
                ? "응답 연결이 끊겨 답을 기다리는 중이다. 끝나면 답이 나타난다."
                : "다른 창에서 답하는 중이다. 끝나면 이 창에도 답이 나타난다."}
            </p>
          ) : null}
          <Composer
            key={composerGeneration}
            value={draft}
            onChange={setDraft}
            onSend={(attachmentIds) => send(attachmentIds)}
            disabled={conversationId === null && agents.length === 0}
            conversationId={conversationId}
            agentCode={agentCode}
            acceptsAttachments={currentAgent?.acceptsAttachments ?? false}
            onConversationCreated={(id) => {
              if (conversationIdRef.current === null) {
                window.history.replaceState(null, "", `/chat/${id}`);
              }
              conversationIdRef.current = id;
              setConversationId(id);
              void refresh();
            }}
            running={sending}
            canStop={executionId !== null && !stopRequested}
            onStop={() => { void stop(); }}
            mention={startScreen && !agentLocked && agents.length > 0
              ? { agents, onPick: setAgentCode } : undefined}
            onBlockingChange={setComposerBlocking}
          />
          {startScreen ? (
            <StarterPrompts
              prompts={currentAgent?.starterPrompts ?? []}
              disabled={sending || composerBlocking}
              onPrompt={(text) => { void send([], text); }}
            />
          ) : null}
        </div>
      </div>
      {panelTarget?.kind === "activity" ? <ActivityPanel target={panelTarget.target.mode === "live" && activity
        ? { mode: "live", state: activity } : panelTarget.target} onClose={() => setPanelTarget(null)} /> : null}
      {panelTarget?.kind === "artifact" && conversationId !== null ? <ArtifactPanel key={panelTarget.path}
        conversationId={conversationId} path={panelTarget.path} onClose={() => setPanelTarget(null)} /> : null}
    </section>
  );
}
