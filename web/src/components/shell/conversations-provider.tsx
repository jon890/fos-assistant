"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { describeError } from "@/components/error-message";

export type Conversation = {
  id: string;
  title: string;
  agentCode: string | null;
  agentName: string | null;
  updatedAt: string;
  /** 대화에서 고른 모델 제공사다. 고르지 않았으면 null 이고 profile 의 기본값으로 돈다 */
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

type ErrorPayload = { code?: string; message?: string };

type ConversationsValue = {
  conversations: Conversation[];
  loading: boolean;
  error: string | null;
  newConversationVersion: number;
  refresh(): Promise<void>;
  startNew(): void;
  rename(id: string, title: string): Promise<void>;
  /** 서버가 돌려준 대화 한 줄로 목록의 그 줄을 바꾼다. 목록에 없으면 앞에 더한다 */
  replace(conversation: Conversation): void;
  remove(id: string): Promise<void>;
};

const ConversationsContext = createContext<ConversationsValue | null>(null);

async function failure(response: Response): Promise<Error> {
  const payload = (await response.json().catch(() => ({}))) as ErrorPayload;
  return new Error(
    describeError(
      payload.code ?? "INTERNAL_ERROR",
      payload.message ?? "대화 목록을 읽지 못했어요.",
    ),
  );
}

export function ConversationsProvider({
  enabled,
  children,
}: {
  enabled: boolean;
  children: React.ReactNode;
}) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);
  const [newConversationVersion, setNewConversationVersion] = useState(0);
  /**
   * 목록을 바꾼 마지막 요청의 순번이다. 목록 읽기와 `replace` 가 올린다.
   *
   * <p>새 대화에서 모델을 고르면 빈 대화를 만든 뒤의 목록 읽기와 모델 저장이 함께 나간다. 목록 응답이 저장
   * 응답보다 늦게 오면 `replace` 로 바꾼 줄이 저장 전의 줄로 되돌아간다. 그래서 응답이 왔을 때 순번이 그새 올랐으면 버린다.
   */
  const latestRequest = useRef(0);
  /** 마지막으로 나간 목록 읽기의 순번이다. 응답을 `replace` 때문에 버렸는지 구분한다 */
  const latestLoad = useRef(0);

  const refresh = useCallback(async () => {
    if (!enabled) return;

    /** 목록을 한 번 읽는다. `replace` 때문에 응답을 버렸으면 참을 돌려준다 */
    async function load(): Promise<boolean> {
      const request = ++latestRequest.current;
      latestLoad.current = request;
      try {
        const response = await fetch("/api/chat/conversations", {
          cache: "no-store",
        });
        if (!response.ok) throw await failure(response);
        const loaded = (await response.json()) as Conversation[];
        if (request !== latestRequest.current)
          return request === latestLoad.current;
        setConversations(loaded);
        setError(null);
      } catch (reason) {
        if (request !== latestRequest.current)
          return request === latestLoad.current;
        setError(
          reason instanceof Error
            ? reason.message
            : "대화 목록을 읽지 못했어요.",
        );
      } finally {
        // 버린 응답이어도 첫 읽기의 뼈대는 거둔다. 순번을 올린 `replace` 는 뼈대를 거두지 않는다.
        setLoading(false);
      }
      return false;
    }

    // `replace` 는 한 줄만 바꾸므로, 버린 응답에 담긴 다른 줄의 제목과 순서는 옛 값으로 남는다. 그래서 한 번 더 읽는다.
    // 뒤에 나간 목록 읽기 때문에 버렸으면 그 읽기가 목록을 채우므로 다시 읽지 않는다. 다시 읽기도 한 번뿐이라 되풀이되지 않는다.
    if (await load()) await load();
  }, [enabled]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const startNew = useCallback(
    () => setNewConversationVersion((version) => version + 1),
    [],
  );

  const rename = useCallback(async (id: string, title: string) => {
    const response = await fetch(`/api/chat/conversations/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title }),
    });
    if (!response.ok) throw await failure(response);
    const updated = (await response.json()) as Conversation;
    setConversations((current) =>
      current.map((item) => (item.id === id ? updated : item)),
    );
  }, []);

  const replace = useCallback((conversation: Conversation) => {
    latestRequest.current += 1;
    setConversations((current) =>
      current.some((item) => item.id === conversation.id)
        ? current.map((item) =>
            item.id === conversation.id ? conversation : item,
          )
        : [conversation, ...current],
    );
  }, []);

  const remove = useCallback(async (id: string) => {
    const response = await fetch(`/api/chat/conversations/${id}`, {
      method: "DELETE",
    });
    if (!response.ok) throw await failure(response);
    setConversations((current) => current.filter((item) => item.id !== id));
  }, []);

  const value = useMemo(
    () => ({
      conversations,
      loading,
      error,
      refresh,
      startNew,
      newConversationVersion,
      rename,
      replace,
      remove,
    }),
    [
      conversations,
      loading,
      error,
      refresh,
      startNew,
      newConversationVersion,
      rename,
      replace,
      remove,
    ],
  );

  return (
    <ConversationsContext.Provider value={value}>
      {children}
    </ConversationsContext.Provider>
  );
}

export function useConversations(): ConversationsValue {
  const value = useContext(ConversationsContext);
  if (!value) throw new Error("ConversationsProvider 가 필요하다.");
  return value;
}
