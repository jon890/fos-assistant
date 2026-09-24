"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { describeError } from "@/components/error-message";

export type Conversation = {
  id: number;
  title: string;
  agentCode: string;
  agentName: string;
  updatedAt: string;
};

type ErrorPayload = { code?: string; message?: string };

type ConversationsValue = {
  conversations: Conversation[];
  loading: boolean;
  error: string | null;
  newConversationVersion: number;
  refresh(): Promise<void>;
  startNew(): void;
  rename(id: number, title: string): Promise<void>;
  remove(id: number): Promise<void>;
};

const ConversationsContext = createContext<ConversationsValue | null>(null);

async function failure(response: Response): Promise<Error> {
  const payload = (await response.json().catch(() => ({}))) as ErrorPayload;
  return new Error(describeError(payload.code ?? "INTERNAL_ERROR", payload.message ?? "대화 목록을 읽지 못했다."));
}

export function ConversationsProvider({ enabled, children }: {
  enabled: boolean;
  children: React.ReactNode;
}) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);
  const [newConversationVersion, setNewConversationVersion] = useState(0);

  const refresh = useCallback(async () => {
    if (!enabled) return;
    try {
      const response = await fetch("/api/chat/conversations", { cache: "no-store" });
      if (!response.ok) throw await failure(response);
      setConversations((await response.json()) as Conversation[]);
      setError(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "대화 목록을 읽지 못했다.");
    } finally {
      setLoading(false);
    }
  }, [enabled]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const startNew = useCallback(() => setNewConversationVersion((version) => version + 1), []);

  const rename = useCallback(async (id: number, title: string) => {
    const response = await fetch(`/api/chat/conversations/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title }),
    });
    if (!response.ok) throw await failure(response);
    const updated = (await response.json()) as Conversation;
    setConversations((current) => current.map((item) => item.id === id ? updated : item));
  }, []);

  const remove = useCallback(async (id: number) => {
    const response = await fetch(`/api/chat/conversations/${id}`, { method: "DELETE" });
    if (!response.ok) throw await failure(response);
    setConversations((current) => current.filter((item) => item.id !== id));
  }, []);

  const value = useMemo(() => ({
    conversations, loading, error, refresh, startNew, newConversationVersion, rename, remove,
  }), [conversations, loading, error, refresh, startNew, newConversationVersion, rename, remove]);

  return <ConversationsContext.Provider value={value}>{children}</ConversationsContext.Provider>;
}

export function useConversations(): ConversationsValue {
  const value = useContext(ConversationsContext);
  if (!value) throw new Error("ConversationsProvider 가 필요하다.");
  return value;
}
