"use client";

import { useEffect, useRef, useState } from "react";
import { ConversationList, type Conversation } from "./conversation-list";
import { describeError } from "./error-message";

type Turn = {
  id: number | string;
  role: "USER" | "ASSISTANT";
  content: string;
  senderName: string | null;
};
type Workspace = { code: string; name: string; visibility: string };
type Agent = { code: string; name: string; model: string; visibility: string };
type ErrorPayload = { code: string; message: string };

async function readPayload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function ChatPanel() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspaceCode, setWorkspaceCode] = useState<string>("");
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentCode, setAgentCode] = useState<string>("");
  const loadingConversation = useRef<number | null>(null);
  const selectionVersion = useRef(0);

  useEffect(() => {
    fetch("/api/workspaces")
      .then((response) => (response.ok ? response.json() : []))
      .then((data: Workspace[]) => setWorkspaces(data))
      .catch(() => setWorkspaces([]));
  }, []);

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
        if (!latest) return;

        setConversationId(latest.id);
        setWorkspaceCode(latest.workspaceCode ?? "");
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
        if (selectionVersion.current === version) loadingConversation.current = null;
      }
    }

    void loadInitialConversation();
    return () => {
      cancelled = true;
    };
  }, []);

  // 대화가 시작된 뒤에는 그 대화의 영역이 고정된다. 요청 본문을 바꿔도 서버가 무시하므로 화면도 잠근다.
  const workspaceLocked = conversationId !== null;
  const agentLocked = conversationId !== null;

  async function selectConversation(conversation: Conversation) {
    if (loadingConversation.current !== null || sending) return;

    const version = ++selectionVersion.current;
    loadingConversation.current = conversation.id;
    setConversationId(conversation.id);
    setWorkspaceCode(conversation.workspaceCode ?? "");
    setAgentCode(conversation.agentCode);
    setTurns([]);
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
      if (selectionVersion.current === version) loadingConversation.current = null;
    }
  }

  function startNewConversation() {
    if (sending) return;
    selectionVersion.current += 1;
    loadingConversation.current = null;
    setConversationId(null);
    setWorkspaceCode("");
    setAgentCode(agents[0]?.code ?? "");
    setTurns([]);
    setError(null);
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

  async function send(event: React.FormEvent) {
    event.preventDefault();
    const text = draft.trim();
    if (text.length === 0 || sending || agentCode.length === 0) return;

    const pendingId = `pending-${Date.now()}`;
    setSending(true);
    setError(null);
    setDraft("");
    setTurns((previous) => [
      ...previous,
      { id: pendingId, role: "USER", content: text, senderName: null },
    ]);

    const restoreFailedMessage = () => {
      setDraft(text);
      setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
    };

    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          conversationId,
          text,
          workspaceCode: workspaceCode.length > 0 ? workspaceCode : null,
          agentCode,
        }),
      });
      const payload = await readPayload<
        ErrorPayload & { conversationId: number; assistantText: string }
      >(response);
      if (!response.ok) {
        restoreFailedMessage();
        setError(describeError(payload.code, payload.message));
        return;
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
      try {
        await Promise.all([refreshConversations(), refreshMessages(payload.conversationId)]);
      } catch (reason) {
        setError(reason instanceof Error ? reason.message : "대화 이력을 다시 읽지 못했다.");
      }
    } catch {
      restoreFailedMessage();
      setError("요청을 보내지 못했다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="grid gap-6 md:grid-cols-[16rem_minmax(0,1fr)]">
      <ConversationList
        conversations={conversations}
        selectedId={conversationId}
        onSelect={(conversation) => void selectConversation(conversation)}
        onNew={startNewConversation}
      />

      <div className="flex min-w-0 flex-col gap-4">
        <label className="flex items-center gap-2 text-xs" style={{ color: "var(--muted)" }}>
          에이전트
          {!agentLocked && agents.length > 1 ? (
            <select
              value={agentCode}
              onChange={(event) => setAgentCode(event.target.value)}
              className="rounded-md border px-2 py-1 text-xs"
              style={{ borderColor: "var(--border)", background: "transparent" }}
            >
              {agents.map((agent) => (
                <option key={agent.code} value={agent.code}>{agent.name}</option>
              ))}
            </select>
          ) : (
            <span>{agents.find((agent) => agent.code === agentCode)?.name ?? "등록된 에이전트 없음"}</span>
          )}
          {agentLocked ? <span>이 대화는 에이전트가 고정됐다.</span> : null}
        </label>

        <label className="flex items-center gap-2 text-xs" style={{ color: "var(--muted)" }}>
          작업 영역
          <select
            value={workspaceCode}
            onChange={(event) => setWorkspaceCode(event.target.value)}
            disabled={workspaceLocked}
            className="rounded-md border px-2 py-1 text-xs disabled:opacity-50"
            style={{ borderColor: "var(--border)", background: "transparent" }}
          >
            <option value="">영역 없음</option>
            {workspaces.map((workspace) => (
              <option key={workspace.code} value={workspace.code}>
                {workspace.name}
              </option>
            ))}
          </select>
          {workspaceLocked ? <span>이 대화는 영역이 고정됐다.</span> : null}
        </label>

        <ol className="flex flex-col gap-3">
          {turns.map((turn) => (
            <li
              key={turn.id}
              className="rounded-lg px-3 py-2 text-sm whitespace-pre-wrap"
              style={{
                background: turn.role === "USER" ? "var(--surface)" : "transparent",
                border: turn.role === "ASSISTANT" ? "1px solid var(--border)" : "none",
              }}
            >
              <span className="mb-1 block text-xs" style={{ color: "var(--muted)" }}>
                {turn.role === "USER" ? turn.senderName : "비서"}
              </span>
              {turn.content}
            </li>
          ))}
          {sending ? (
            <li className="text-sm" style={{ color: "var(--muted)" }}>
              비서가 실행 중이다.
            </li>
          ) : null}
        </ol>

        {error ? (
          <p className="rounded-md px-3 py-2 text-sm" style={{ background: "var(--surface)" }}>
            {error}
          </p>
        ) : null}

        {agents.length === 0 ? (
          <p className="rounded-md px-3 py-2 text-sm" style={{ background: "var(--surface)" }}>
            사용할 수 있는 에이전트가 없다. 관리자에게 에이전트 등록을 요청한다.
          </p>
        ) : null}

        <form onSubmit={send} className="flex gap-2">
          <input
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            disabled={sending || agents.length === 0}
            placeholder="무엇을 도와줄까요"
            className="flex-1 rounded-md border px-3 py-2 text-sm disabled:opacity-50"
            style={{ borderColor: "var(--border)", background: "transparent" }}
          />
          <button
            type="submit"
            disabled={sending || agents.length === 0}
            className="rounded-md border px-4 py-2 text-sm disabled:opacity-50"
            style={{ borderColor: "var(--border)" }}
          >
            보내기
          </button>
        </form>
      </div>
    </section>
  );
}
