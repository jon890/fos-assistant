"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import { describeError } from "./error-message";
import { Composer } from "./chat/composer";
import { StartScreenHeader, StarterPrompts } from "./chat/start-screen";
import { MessageList } from "./chat/message-list";
import { applyChatEvent, emptyActivity, failActivity, type ActivityState } from "./chat/activity/activity-state";
import { ActivityPanel, type ActivityPanelTarget } from "./chat/activity/activity-panel";
import type { Turn } from "./chat/message-bubble";
import { useConversations } from "./shell/conversations-provider";
import { useShellDisplayName, useShellTitle } from "./shell/app-shell";
import { readEventStream } from "@/lib/stream";
import type { ChatEvent } from "@/lib/chat-event";
import { foldVersions } from "@/lib/message-versions";
import type { AgentView } from "@/lib/agent";

type ErrorPayload = { code: string; message: string };
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
 * 흐름이 오래 걸린다고 알리기까지 기다리는 시간이다.
 *
 * <p>실측한 포지션 추천 하나가 15분 넘게 걸렸고, 넷으로 나누면 더 걸릴 수도 있다. 2분이 지나면 한 번만
 * 알리고 그 뒤로는 다시 알리지 않는다.
 */
const SLOW_FLOW_MS = 120_000;

export function ChatPanel({ initialConversationId }: { initialConversationId: number | null }) {
  const pathname = usePathname();
  const { conversations, refresh, newConversationVersion } = useConversations();
  const displayName = useShellDisplayName();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(initialConversationId);
  const conversationIdRef = useRef<number | null>(initialConversationId);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [activity, setActivity] = useState<ActivityState | null>(null);
  const [liveExpanded, setLiveExpanded] = useState(false);
  const liveExpandedRef = useRef(false);
  const [expandedOnDone, setExpandedOnDone] = useState<{ executionId: number; expanded: boolean } | null>(null);
  const [panelTarget, setPanelTarget] = useState<ActivityPanelTarget | null>(null);
  const currentExecutionId = useRef<number | null>(null);
  const [executionId, setExecutionId] = useState<number | null>(null);
  const [stopRequested, setStopRequested] = useState(false);
  const [selectedVersions, setSelectedVersions] = useState<Record<number, number>>({});
  const [editingMessageId, setEditingMessageId] = useState<number | null>(null);
  const [editText, setEditText] = useState<string | null>(null);
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
   * 대화를 만들어 `conversationId` 가 null 에서 번호로 바뀌는데, 그 순간 Composer 가 다시 만들어져
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
    setError(null);
    setTurnError(null);
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
    setEditingMessageId(null);
    setEditText(null);
    setComposerGeneration((generation) => generation + 1);
    void (async () => {
      try {
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
      if (event.key !== "Escape" || event.isComposing || event.defaultPrevented) return;
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
    setEditingMessageId(null);
    setEditText(null);
    setFlowIsSlow(false);
    setError(null);
    setTurnError(null);
    setNotFound(false);
    setDraft("");
    setSending(false);
    setMessagesLoading(false);
  }

  async function refreshMessages(id: number, version: number): Promise<Turn[]> {
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
        if (event.type === "stopped") {
          setActivity((previous) => previous && applyChatEvent(previous, event));
        }
        const finishedExecutionId = event.executionId ?? currentExecutionId.current;
        if (finishedExecutionId !== null) {
          setExpandedOnDone({ executionId: finishedExecutionId, expanded: liveExpandedRef.current });
          setPanelTarget((previous) => previous?.mode === "live"
            ? { mode: "saved", executionId: finishedExecutionId } : previous);
        }
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
  async function send(attachmentIds: number[], editOfMessageId?: number, replacementText?: string): Promise<boolean> {
    const text = (replacementText ?? draft).trim();
    if (text.length === 0 || sending || (conversationId === null && agentCode.length === 0)) return false;

    const version = selectionVersion.current;
    const editedSlotId = editOfMessageId === undefined ? undefined : latestSlots()?.userVersion.slotId;
    const pendingId = `${editOfMessageId === undefined ? "pending" : "edit-pending"}-${Date.now()}`;
    const assistantPendingId = `assistant-${Date.now()}`;
    setSending(true);
    setError(null);
    setTurnError(null);
    // 글을 인자로 받았으면 입력창의 글과 무관하게 보낸다. 수정과 추천 질문이 그렇다.
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
    const closeEditAfterStartedFailure = () => {
      if (editOfMessageId === undefined || selectionVersion.current !== version) return;
      setEditingMessageId(null);
      setEditText(null);
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
    const startedFailureMessage = (message: string, refreshed: boolean) => refreshed
      ? message
      : `${message} 대화 이력을 다시 읽지 못했다. 아래에서 다시 시도하거나 대화를 새로고침해 주세요.`;

    const requestBody = {
      conversationId,
      text,
      agentCode,
      attachmentIds,
      ...(editOfMessageId === undefined ? {} : { editOfMessageId }),
    };

    const sendWithoutStream = async () => {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestBody),
      });
      const payload = await readPayload<
        ErrorPayload & { conversationId: number; assistantText: string }
      >(response);
      if (!response.ok) {
        restoreFailedMessage();
        setError(describeError(payload.code, payload.message));
        return false;
      }
      if (selectionVersion.current !== version) return true;
      if (conversationIdRef.current === null) {
        window.history.replaceState(null, "", `/c/${payload.conversationId}`);
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
        if (editOfMessageId !== undefined) {
          restoreFailedMessage();
          setError("수정 요청을 보내지 못했다.");
          return false;
        }
        return await sendWithoutStream();
      }

      if (!response.ok) {
        if ([404, 405, 415, 501].includes(response.status)) {
          if (editOfMessageId !== undefined) {
            restoreFailedMessage();
            setError("수정 스트림을 열지 못했다.");
            return false;
          }
          return await sendWithoutStream();
        }
        const payload = await readPayload<ErrorPayload>(response);
        restoreFailedMessage();
        setError(describeError(payload.code, payload.message));
        if (payload.code === "MESSAGE_NOT_LATEST" && conversationIdRef.current !== null) {
          await refreshMessages(conversationIdRef.current, version);
        }
        return false;
      }

      const stream = { started: false, done: false, reportedError: false };
      try {
        await consumeTurnStream(response, version, stream, {
          onStarted: (event) => {
            if (!event.conversationId) return;
            if (conversationIdRef.current === null) window.history.replaceState(null, "", `/c/${event.conversationId}`);
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
            clearSelectedSlot(editedSlotId);
          },
          onError: async (event) => {
            const message = describeError(event.code ?? "INTERNAL_ERROR", event.message ?? "요청을 처리하지 못했다.");
            if (!stream.started && event.code === "MESSAGE_NOT_LATEST" && conversationIdRef.current !== null) {
              await refreshMessages(conversationIdRef.current, version);
            }
            if (stream.started && conversationIdRef.current !== null) {
              closeEditAfterStartedFailure();
              const refreshed = await refreshAfterStartedFailure();
              setTurnError(startedFailureMessage(message, refreshed));
            } else {
              restoreFailedMessage();
              setError(message);
            }
          },
        });
      } catch {
        if (!stream.done && !stream.reportedError) {
          finishFailedActivity();
          const message = describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다.");
          if (stream.started && conversationIdRef.current !== null) {
            closeEditAfterStartedFailure();
            const refreshed = await refreshAfterStartedFailure();
            if (selectionVersion.current === version) setTurnError(startedFailureMessage(message, refreshed));
          } else {
            restoreFailedMessage();
            if (selectionVersion.current === version) setError(message);
          }
          return editOfMessageId === undefined && stream.started;
        }
        return editOfMessageId === undefined
          ? stream.started || stream.done : stream.done && !stream.reportedError;
      }
      if (!stream.done && !stream.reportedError) {
        finishFailedActivity();
        const message = describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다.");
        if (stream.started && conversationIdRef.current !== null) {
          closeEditAfterStartedFailure();
          const refreshed = await refreshAfterStartedFailure();
          if (selectionVersion.current === version) setTurnError(startedFailureMessage(message, refreshed));
        } else {
          restoreFailedMessage();
          if (selectionVersion.current === version) setError(message);
        }
        return editOfMessageId === undefined && stream.started;
      }
      return editOfMessageId === undefined
        ? stream.started || stream.done : stream.done && !stream.reportedError;
    } catch (reason) {
      finishFailedActivity();
      restoreFailedMessage();
      setError(reason instanceof Error ? reason.message : "요청을 보내지 못했다.");
      return false;
    } finally {
      if (selectionVersion.current === version) setSending(false);
    }
  }

  async function regenerate() {
    if (conversationId === null || sending) return;
    const version = selectionVersion.current;
    const regeneratedSlotId = latestSlots()?.answers.at(-1)?.version.slotId;
    const pendingId = `assistant-regenerate-${Date.now()}`;
    const stream = { started: false, done: false, reportedError: false };
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
      if (!stream.done && !stream.reportedError) throw new Error(describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다."));
    } catch (reason) {
      if (selectionVersion.current === version) {
        setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
        setActivity((previous) => previous && failActivity(previous, Date.now()));
        setTurnError(reason instanceof Error ? reason.message : "다시 생성하지 못했다.");
        await refreshMessages(conversationId, version).catch(() => {});
      }
    } finally {
      if (selectionVersion.current === version) {
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
        <Link href="/" className="mt-4 inline-block rounded-md bg-surface px-3 py-2 text-sm">새 대화</Link>
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
            <div className="flex min-w-0 flex-1 items-center gap-3 overflow-hidden text-xs text-muted">
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
          onOpenSaved={(executionId) => setPanelTarget({ mode: "saved", executionId })}
          onOpenLive={() => { if (activity) setPanelTarget({ mode: "live", state: activity }); }}
          selectedVersions={selectedVersions}
          onVersionChange={(slotId, index) => setSelectedVersions((previous) => ({ ...previous, [slotId]: index }))}
          onRegenerate={() => { void regenerate(); }}
          editingMessageId={editingMessageId}
          editText={editText}
          onEditTextChange={setEditText}
          onStartEdit={(id, text) => { setEditingMessageId(id); setEditText(text); }}
          onEdit={async (id, text) => {
            const ok = await send([], id, text);
            if (ok) { setEditingMessageId(null); setEditText(null); }
          }}
          onEditCancel={() => { setEditingMessageId(null); setEditText(null); }}
          onRetry={() => { void regenerate(); }}
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
          {error ? <p className="mb-2 rounded-md bg-surface px-3 py-2 text-sm">{error}</p> : null}
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
                window.history.replaceState(null, "", `/c/${id}`);
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
              onPrompt={(text) => { void send([], undefined, text); }}
            />
          ) : null}
        </div>
      </div>
      {panelTarget ? <ActivityPanel target={panelTarget.mode === "live" && activity
        ? { mode: "live", state: activity } : panelTarget} onClose={() => setPanelTarget(null)} /> : null}
    </section>
  );
}
