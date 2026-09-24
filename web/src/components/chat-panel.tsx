"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import { describeError } from "./error-message";
import { Composer } from "./chat/composer";
import { MessageList } from "./chat/message-list";
import { applyChatEvent, emptyActivity, type ActivityState } from "./chat/activity/activity-state";
import type { Turn } from "./chat/message-bubble";
import { useConversations } from "./shell/conversations-provider";
import { useShellTitle } from "./shell/app-shell";
import { readEventStream } from "@/lib/stream";
import type { ChatEvent } from "@/lib/chat-event";

type Agent = {
  code: string;
  name: string;
  model: string;
  visibility: string;
  acceptsAttachments: boolean;
};
type ErrorPayload = { code: string; message: string };
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
  const { conversations, loading: conversationsLoading, refresh, newConversationVersion } = useConversations();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(initialConversationId);
  const conversationIdRef = useRef<number | null>(initialConversationId);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [activity, setActivity] = useState<ActivityState | null>(null);
  const [flowIsSlow, setFlowIsSlow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [turnError, setTurnError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentCode, setAgentCode] = useState<string>("");
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
      .then((data: Agent[]) => {
        setAgents(data);
        setAgentCode((current) => current || data[0]?.code || "");
      })
      .catch(() => setAgents([]));
  }, []);

  useEffect(() => {
    if (initialConversationId === null) return;
    const version = ++selectionVersion.current;
    conversationIdRef.current = initialConversationId;
    setConversationId(initialConversationId);
    setMessagesLoading(true);
    setNotFound(false);
    setError(null);
    setTurnError(null);
    setTurns([]);
    setActivity(null);
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
      // 더 안쪽의 화면과 중지 동작이 추가될 때 이 처리기에서 우선순위를 정한다.
    };
    window.addEventListener("keydown", onEscape);
    return () => window.removeEventListener("keydown", onEscape);
  }, []);

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
    setAgentCode(agents[0]?.code ?? "");
    setTurns([]);
    setActivity(null);
    setFlowIsSlow(false);
    setError(null);
    setTurnError(null);
    setNotFound(false);
    setDraft("");
    setSending(false);
    setMessagesLoading(false);
  }

  async function refreshMessages(id: number, version: number) {
    const response = await fetch(`/api/chat/conversations/${id}/messages`);
    if (!response.ok) {
      const payload = await readPayload<ErrorPayload>(response);
      throw new Error(describeError(payload.code, payload.message));
    }
    const loaded = await readPayload<Turn[]>(response);
    if (selectionVersion.current === version) setTurns(loaded);
  }

  /** 전송이 실제로 끝났는지를 돌려준다. `Composer` 는 이 값을 보고 실패했을 때 미리보기를 남긴다 */
  async function send(attachmentIds: number[]): Promise<boolean> {
    const text = draft.trim();
    if (text.length === 0 || sending || (conversationId === null && agentCode.length === 0)) return false;

    const version = selectionVersion.current;
    const pendingId = `pending-${Date.now()}`;
    const assistantPendingId = `assistant-${Date.now()}`;
    setSending(true);
    setError(null);
    setTurnError(null);
    setDraft("");
    setActivity(emptyActivity(Date.now()));
    setFlowIsSlow(false);
    setTurns((previous) => [
      ...previous,
      { id: pendingId, role: "USER", content: text, senderName: null },
    ]);

    const restoreFailedMessage = () => {
      if (selectionVersion.current !== version) return;
      setDraft(text);
      setTurns((previous) =>
        previous.filter((turn) => turn.id !== pendingId && turn.id !== assistantPendingId),
      );
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

      let done = false;
      let started = false;
      let reportedError = false;
      try {
        await readEventStream<ChatEvent>(response, async (streamEvent) => {
          if (selectionVersion.current !== version) return;
          if (streamEvent.type === "started" && streamEvent.conversationId) {
            started = true;
            if (conversationIdRef.current === null) {
              window.history.replaceState(null, "", `/c/${streamEvent.conversationId}`);
            }
            conversationIdRef.current = streamEvent.conversationId;
            setConversationId(streamEvent.conversationId);
            void refresh();
          } else if (streamEvent.type === "delta" && streamEvent.text) {
            setTurns((previous) => {
              const current = previous.find((turn) => turn.id === assistantPendingId);
              if (!current) {
                return [
                  ...previous,
                  { id: assistantPendingId, role: "ASSISTANT", content: streamEvent.text ?? "", senderName: null },
                ];
              }
              return previous.map((turn) =>
                turn.id === assistantPendingId
                  ? { ...turn, content: turn.content + (streamEvent.text ?? "") }
                  : turn,
              );
            });
          } else if (streamEvent.type === "reset") {
            // 막혀서 넘어간 시도의 조각이다. 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
            setTurns((previous) => previous.filter((turn) => turn.id !== assistantPendingId));
            setActivity((previous) => previous && applyChatEvent(previous, streamEvent));
          } else if (["tool", "subagent", "step", "switched"].includes(streamEvent.type)) {
            setActivity((previous) => previous && applyChatEvent(previous, streamEvent));
          } else if (streamEvent.type === "done" && streamEvent.conversationId) {
            done = true;
            conversationIdRef.current = streamEvent.conversationId;
            setConversationId(streamEvent.conversationId);
            await Promise.all([
              refresh(),
              refreshMessages(streamEvent.conversationId, version),
            ]);
            setActivity(null);
            setFlowIsSlow(false);
          } else if (streamEvent.type === "error") {
            reportedError = true;
            const message = describeError(streamEvent.code ?? "INTERNAL_ERROR", streamEvent.message ?? "요청을 처리하지 못했다.");
            if (started && conversationIdRef.current !== null) {
              await refreshMessages(conversationIdRef.current, version);
              setTurnError(message);
            } else {
              restoreFailedMessage();
              setError(message);
            }
          }
        });
      } catch {
        if (!done && !reportedError) {
          const message = describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다.");
          if (started && conversationIdRef.current !== null) {
            await refreshMessages(conversationIdRef.current, version).catch(() => {});
            if (selectionVersion.current === version) setTurnError(message);
          } else {
            restoreFailedMessage();
            if (selectionVersion.current === version) setError(message);
          }
          return started;
        }
        return started || done;
      }
      if (!done && !reportedError) {
        const message = describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다.");
        if (started && conversationIdRef.current !== null) {
          await refreshMessages(conversationIdRef.current, version).catch(() => {});
          if (selectionVersion.current === version) setTurnError(message);
        } else {
          restoreFailedMessage();
          if (selectionVersion.current === version) setError(message);
        }
        return started;
      }
      return started || done;
    } catch (reason) {
      restoreFailedMessage();
      setError(reason instanceof Error ? reason.message : "요청을 보내지 못했다.");
      return false;
    } finally {
      if (selectionVersion.current === version) setSending(false);
    }
  }

  const selectedAgent = conversations.find((item) => item.id === conversationId)?.agentName
    ?? agents.find((agent) => agent.code === agentCode)?.name;
  useShellTitle(selectedAgent ?? null);

  if (notFound) {
    return (
      <section data-testid="conversation-not-found" className="mx-auto max-w-3xl py-12 text-center">
        <h1 className="text-lg font-semibold">대화를 찾을 수 없다</h1>
        <Link href="/" className="mt-4 inline-block rounded-md bg-surface px-3 py-2 text-sm">새 대화</Link>
      </section>
    );
  }

  return (
    <section className="flex h-full min-h-0 min-w-0">
      <h1 className="sr-only">대화</h1>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <div className="flex min-w-0 items-center gap-3 border-b border-border pb-3">
          <div className="flex min-w-0 flex-1 items-center gap-3 overflow-hidden text-xs text-muted">
            <label className={`min-w-0 items-center gap-2 ${agentLocked ? "hidden md:flex" : "flex"}`}>
              <span className="shrink-0">에이전트</span>
              {!agentLocked && agents.length > 1 ? (
                <select
                  value={agentCode}
                  onChange={(event) => setAgentCode(event.target.value)}
                  className="min-w-0 max-w-44 truncate rounded-md border border-border bg-transparent px-2 py-1 text-xs"
                >
                  {agents.map((agent) => (
                    <option key={agent.code} value={agent.code}>{agent.name}</option>
                  ))}
                </select>
              ) : (
                <span className="truncate" title={agents.find((agent) => agent.code === agentCode)?.name}>
                  {agents.find((agent) => agent.code === agentCode)?.name ?? "등록된 에이전트 없음"}
                </span>
              )}
            </label>
          </div>
        </div>

        <MessageList
          turns={turns}
          loading={messagesLoading}
          sending={sending}
          activity={activity}
          conversationId={conversationId}
          flowIsSlow={flowIsSlow}
          turnError={turnError}
        />

        {error ? <p className="mb-2 rounded-md bg-surface px-3 py-2 text-sm">{error}</p> : null}
        {agents.length === 0 && !conversationsLoading ? (
          <p className="mb-2 rounded-md bg-surface px-3 py-2 text-sm">
            사용할 수 있는 에이전트가 없다. 관리자에게 에이전트 등록을 요청한다.
          </p>
        ) : null}
        <Composer
          key={composerGeneration}
          value={draft}
          onChange={setDraft}
          onSend={(attachmentIds) => send(attachmentIds)}
          disabled={sending || (conversationId === null && agents.length === 0)}
          conversationId={conversationId}
          agentCode={agentCode}
          acceptsAttachments={agents.find((agent) => agent.code === agentCode)?.acceptsAttachments ?? false}
          onConversationCreated={(id) => {
            if (conversationIdRef.current === null) {
              window.history.replaceState(null, "", `/c/${id}`);
            }
            conversationIdRef.current = id;
            setConversationId(id);
            void refresh();
          }}
        />
      </div>
    </section>
  );
}
