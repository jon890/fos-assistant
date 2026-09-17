"use client";

import { useEffect, useRef, useState } from "react";
import { describeError } from "./error-message";
import { Composer } from "./chat/composer";
import { ConversationDrawer } from "./chat/conversation-drawer";
import { ConversationList, type Conversation } from "./chat/conversation-list";
import { MessageList } from "./chat/message-list";
import type { Turn } from "./chat/message-bubble";
import { IconButton } from "./ui/icon-button";
import { readEventStream } from "@/lib/stream";

type Agent = { code: string; name: string; model: string; visibility: string };
type ErrorPayload = { code: string; message: string };
type ChatEvent = {
  type: "delta" | "tool" | "done" | "error";
  text?: string | null;
  toolName?: string | null;
  detail?: string | null;
  conversationId?: number | null;
  messageId?: number | null;
  executionId?: number | null;
  code?: string | null;
  message?: string | null;
};

async function readPayload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function ChatPanel() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [toolEvents, setToolEvents] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentCode, setAgentCode] = useState<string>("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [conversationsLoading, setConversationsLoading] = useState(true);
  const [messagesLoading, setMessagesLoading] = useState(true);
  const loadingConversation = useRef<number | null>(null);
  const selectionVersion = useRef(0);

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
    let cancelled = false;

    async function loadInitialConversation() {
      const version = selectionVersion.current;
      try {
        const response = await fetch("/api/chat/conversations");
        if (!response.ok) {
          const payload = await readPayload<ErrorPayload>(response);
          throw new Error(describeError(payload.code, payload.message));
        }
        const loaded = await readPayload<Conversation[]>(response);
        if (cancelled || selectionVersion.current !== version) return;
        setConversations(loaded);
        const latest = loaded[0];
        if (!latest) {
          setMessagesLoading(false);
          return;
        }

        setConversationId(latest.id);
        setAgentCode(latest.agentCode);
        loadingConversation.current = latest.id;
        const messagesResponse = await fetch(
          `/api/chat/conversations/${latest.id}/messages`,
        );
        if (!messagesResponse.ok) {
          const payload = await readPayload<ErrorPayload>(messagesResponse);
          throw new Error(describeError(payload.code, payload.message));
        }
        const messages = await readPayload<Turn[]>(messagesResponse);
        if (!cancelled && selectionVersion.current === version) setTurns(messages);
      } catch (reason) {
        if (!cancelled && selectionVersion.current === version) {
          setTurns([]);
          setError(reason instanceof Error ? reason.message : "대화 이력을 읽지 못했다.");
        }
      } finally {
        if (!cancelled) {
          setConversationsLoading(false);
          setMessagesLoading(false);
        }
        if (selectionVersion.current === version) loadingConversation.current = null;
      }
    }

    void loadInitialConversation();
    return () => {
      cancelled = true;
    };
  }, []);

  const agentLocked = conversationId !== null;

  async function selectConversation(conversation: Conversation) {
    if (loadingConversation.current !== null || sending) return;

    const version = ++selectionVersion.current;
    loadingConversation.current = conversation.id;
    setDrawerOpen(false);
    setMessagesLoading(true);
    setConversationId(conversation.id);
    setAgentCode(conversation.agentCode);
    setTurns([]);
    setToolEvents([]);
    setError(null);
    try {
      const response = await fetch(`/api/chat/conversations/${conversation.id}/messages`);
      if (!response.ok) {
        const payload = await readPayload<ErrorPayload>(response);
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
        loadingConversation.current = null;
        setMessagesLoading(false);
      }
    }
  }

  function startNewConversation() {
    if (sending) return;
    selectionVersion.current += 1;
    loadingConversation.current = null;
    setConversationId(null);
    setAgentCode(agents[0]?.code ?? "");
    setTurns([]);
    setToolEvents([]);
    setError(null);
    setDrawerOpen(false);
    setMessagesLoading(false);
  }

  async function refreshConversations() {
    const response = await fetch("/api/chat/conversations");
    if (!response.ok) {
      const payload = await readPayload<ErrorPayload>(response);
      throw new Error(describeError(payload.code, payload.message));
    }
    setConversations(await readPayload<Conversation[]>(response));
  }

  async function refreshMessages(id: number) {
    const response = await fetch(`/api/chat/conversations/${id}/messages`);
    if (!response.ok) {
      const payload = await readPayload<ErrorPayload>(response);
      throw new Error(describeError(payload.code, payload.message));
    }
    setTurns(await readPayload<Turn[]>(response));
  }

  async function send() {
    const text = draft.trim();
    if (text.length === 0 || sending || agentCode.length === 0) return;

    const pendingId = `pending-${Date.now()}`;
    const assistantPendingId = `assistant-${Date.now()}`;
    setSending(true);
    setError(null);
    setDraft("");
    setToolEvents([]);
    setTurns((previous) => [
      ...previous,
      { id: pendingId, role: "USER", content: text, senderName: null },
    ]);

    const restoreFailedMessage = () => {
      setDraft(text);
      setTurns((previous) =>
        previous.filter((turn) => turn.id !== pendingId && turn.id !== assistantPendingId),
      );
    };

    const requestBody = {
      conversationId,
      text,
      agentCode,
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
      await Promise.all([refreshConversations(), refreshMessages(payload.conversationId)]);
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
        await sendWithoutStream();
        return;
      }

      if (!response.ok) {
        if ([404, 405, 415, 501].includes(response.status)) {
          await sendWithoutStream();
          return;
        }
        const payload = await readPayload<ErrorPayload>(response);
        restoreFailedMessage();
        setError(describeError(payload.code, payload.message));
        return;
      }

      let done = false;
      let reportedError = false;
      try {
        await readEventStream<ChatEvent>(response, async (streamEvent) => {
          if (streamEvent.type === "delta" && streamEvent.text) {
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
          } else if (streamEvent.type === "tool") {
            const name = streamEvent.toolName ?? "도구";
            const status = streamEvent.detail ?? "진행 중";
            setToolEvents((previous) => [...previous, `${name}: ${status}`]);
          } else if (streamEvent.type === "done" && streamEvent.conversationId) {
            done = true;
            setConversationId(streamEvent.conversationId);
            await Promise.all([
              refreshConversations(),
              refreshMessages(streamEvent.conversationId),
            ]);
            setToolEvents([]);
          } else if (streamEvent.type === "error") {
            reportedError = true;
            restoreFailedMessage();
            setError(describeError(streamEvent.code ?? "INTERNAL_ERROR", streamEvent.message ?? "요청을 처리하지 못했다."));
          }
        });
      } catch {
        if (!done && !reportedError) {
          restoreFailedMessage();
          setError(describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다."));
        }
        return;
      }
      if (!done && !reportedError) {
        restoreFailedMessage();
        setError(describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼다."));
      }
    } catch (reason) {
      restoreFailedMessage();
      setError(reason instanceof Error ? reason.message : "요청을 보내지 못했다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="flex h-full min-h-0 min-w-0">
      <h1 className="sr-only">대화</h1>
      <ConversationDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <ConversationList
          conversations={conversations}
          selectedId={conversationId}
          loading={conversationsLoading}
          onSelect={(conversation) => void selectConversation(conversation)}
          onNew={startNewConversation}
        />
      </ConversationDrawer>

      <div className="flex min-h-0 min-w-0 flex-1 flex-col md:pl-4">
        <div className="flex min-w-0 items-center gap-3 border-b border-border pb-3">
          <IconButton
            label="대화 목록 열기"
            onClick={() => setDrawerOpen(true)}
            className="md:hidden"
          >
            <span aria-hidden="true">☰</span>
          </IconButton>
          <div className="flex min-w-0 flex-1 items-center gap-3 overflow-hidden text-xs text-muted">
            <label className="flex min-w-0 items-center gap-2">
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
          toolEvents={toolEvents}
          conversationId={conversationId}
        />

        {error ? <p className="mb-2 rounded-md bg-surface px-3 py-2 text-sm">{error}</p> : null}
        {agents.length === 0 && !conversationsLoading ? (
          <p className="mb-2 rounded-md bg-surface px-3 py-2 text-sm">
            사용할 수 있는 에이전트가 없다. 관리자에게 에이전트 등록을 요청한다.
          </p>
        ) : null}
        <Composer
          value={draft}
          onChange={setDraft}
          onSend={() => void send()}
          disabled={sending || agents.length === 0}
        />
      </div>
    </section>
  );
}
