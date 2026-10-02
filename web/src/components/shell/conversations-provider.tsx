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
import { coalesce } from "@/lib/coalesce";
import {
  requestConversation,
  requestConversationPage,
} from "@/lib/conversation-list";
import { mergeFirstPage, type ConversationPage } from "./conversation-page";

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
  /** 선택하지 않은 새 대화는 null 이고, 서버가 새 선택 방식을 주면 그 값을 쓴다. */
  modelSelectionMode?: "DEFAULT" | "TIER" | "CUSTOM" | null;
  modelTier?: "FAST" | "BALANCED" | "DEEP" | null;
};

type ErrorPayload = { code?: string; message?: string };

/** 사이드바가 한 번에 읽는 대화 수다. 서버의 기본값과 같다. */
const PAGE_SIZE = 30;

type ConversationsValue = {
  /** 지금까지 읽은 대화다. 최근에 바뀐 것부터 놓이고, 끝에 닿으면 `loadMore` 로 이어 읽는다 */
  conversations: Conversation[];
  loading: boolean;
  error: string | null;
  /** 이어 읽을 대화가 더 있다 */
  hasMore: boolean;
  loadingMore: boolean;
  /** 이어 읽기가 실패했을 때의 안내다. 다음 이어 읽기를 시작하면 지워진다 */
  moreError: string | null;
  /**
   * 다음 쪽을 이어 읽고 새로 더해진 줄을 돌려준다. 이미 읽는 중이거나 더 없으면 빈 배열이다.
   * 검색처럼 많이 필요할 때는 `limit` 로 한 번에 더 읽는다.
   */
  loadMore(limit?: number): Promise<Conversation[]>;
  /** 이어 읽기로 들어온 줄인지. 처음 읽은 줄과 달리 새 대화로 보아 움직이지 않게 할 때 쓴다 */
  wasPaged(id: string): boolean;
  /** 첫 쪽에 없는 오래된 대화를 한 줄로 읽어 둔 것이다. 목록에는 끼지 않는다 */
  pinned: ReadonlyMap<string, Conversation>;
  pin(id: string): Promise<void>;
  newConversationVersion: number;
  /** 목록의 첫 쪽을 다시 읽는다. 짧은 사이에 여러 번 불려도 진행 중인 읽기를 재사용해 많아야 두 번 나간다 */
  refresh(): Promise<void>;
  startNew(): void;
  rename(id: string, title: string): Promise<void>;
  /** 서버가 돌려준 대화 한 줄로 목록의 그 줄을 바꾼다. 목록에 없으면 앞에 더한다 */
  replace(conversation: Conversation): void;
  /** 서버에 지우기 요청만 보낸다. 목록의 줄은 그대로 두고, 실패하면 던진다 */
  remove(id: string): Promise<void>;
  /** 목록에서 그 줄을 뺀다. 지우기 요청이 성공하고 나가는 움직임이 끝난 뒤에 부른다 */
  drop(id: string): void;
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
  const [page, setPage] = useState<ConversationPage>({
    items: [],
    nextCursor: null,
  });
  /** `page` 와 같은 값이다. 이어 읽기가 쓰려고 렌더를 기다리지 않고 바로 읽는 사본이다 */
  const pageRef = useRef(page);
  const [pinned, setPinned] = useState<ReadonlyMap<string, Conversation>>(
    new Map(),
  );
  /** 한 줄 읽기를 이미 시도한 대화다. 없는 대화를 렌더마다 다시 묻지 않는다 */
  const pinTried = useRef(new Set<string>());
  const [loadingMore, setLoadingMore] = useState(false);
  const loadingMoreRef = useRef(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const pagedIds = useRef(new Set<string>());

  /** 쪽을 바꾼다. 렌더를 기다리지 않고 사본을 먼저 바꿔, 잇따른 변경이 서로의 결과를 덮지 않게 한다 */
  const commit = useCallback(
    (update: (current: ConversationPage) => ConversationPage) => {
      pageRef.current = update(pageRef.current);
      setPage(pageRef.current);
    },
    [],
  );
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

  /** 한 쪽을 읽는다. 목록 읽기와 이어 읽기와 한 줄 읽기가 같은 오류 처리를 쓴다 */
  const fetchPage = useCallback(
    async (cursor: string | null, limit: number) => {
      const response = await requestConversationPage(cursor, limit);
      if (!response.ok) throw await failure(response);
      return (await response.json()) as ConversationPage;
    },
    [],
  );

  const readFirstPage = useCallback(async () => {
    /** 첫 쪽을 한 번 읽는다. `replace` 때문에 응답을 버렸으면 참을 돌려준다 */
    async function load(): Promise<boolean> {
      const request = ++latestRequest.current;
      latestLoad.current = request;
      try {
        const loaded = await fetchPage(null, PAGE_SIZE);
        if (request !== latestRequest.current)
          return request === latestLoad.current;
        commit((current) => mergeFirstPage(loaded, current));
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
  }, [commit, fetchPage]);

  // 읽는 중에 불린 호출은 새 요청을 내지 않고 진행 중인 읽기에 얹힌다. 까닭은 `coalesce` 에 있다.
  const coalescedRead = useRef<(() => Promise<void>) | null>(null);
  const refresh = useCallback(() => {
    if (!enabled) return Promise.resolve();
    coalescedRead.current ??= coalesce(readFirstPage);
    return coalescedRead.current();
  }, [enabled, readFirstPage]);

  const loadMore = useCallback(
    async (limit: number = PAGE_SIZE): Promise<Conversation[]> => {
      const cursor = pageRef.current.nextCursor;
      if (!enabled || cursor === null || loadingMoreRef.current) return [];
      loadingMoreRef.current = true;
      setLoadingMore(true);
      setMoreError(null);
      try {
        const next = await fetchPage(cursor, limit);
        const known = new Set(pageRef.current.items.map((item) => item.id));
        const added = next.items.filter((item) => !known.has(item.id));
        for (const item of added) pagedIds.current.add(item.id);
        commit((current) => ({
          items: [...current.items, ...added],
          nextCursor: next.nextCursor,
        }));
        return added;
      } catch (reason) {
        setMoreError(
          reason instanceof Error
            ? reason.message
            : "대화 목록을 더 읽지 못했어요.",
        );
        return [];
      } finally {
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    },
    [commit, enabled, fetchPage],
  );

  const pin = useCallback(async (id: string) => {
    if (pinTried.current.has(id)) return;
    pinTried.current.add(id);
    try {
      const response = await requestConversation(id);
      if (!response.ok) return;
      const found = (await response.json()) as Conversation;
      setPinned((current) => new Map(current).set(id, found));
    } catch {
      // 읽지 못해도 대화 화면은 열린다. 모델 칸만 기본값으로 보인다.
    }
  }, []);

  const wasPaged = useCallback((id: string) => pagedIds.current.has(id), []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const startNew = useCallback(
    () => setNewConversationVersion((version) => version + 1),
    [],
  );

  const rename = useCallback(
    async (id: string, title: string) => {
      const response = await fetch(`/api/chat/conversations/${id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title }),
      });
      if (!response.ok) throw await failure(response);
      const updated = (await response.json()) as Conversation;
      commit((current) => ({
        ...current,
        items: current.items.map((item) => (item.id === id ? updated : item)),
      }));
      setPinned((current) =>
        current.has(id) ? new Map(current).set(id, updated) : current,
      );
    },
    [commit],
  );

  const replace = useCallback(
    (conversation: Conversation) => {
      latestRequest.current += 1;
      const current = pageRef.current;
      if (current.items.some((item) => item.id === conversation.id)) {
        commit((now) => ({
          ...now,
          items: now.items.map((item) =>
            item.id === conversation.id ? conversation : item,
          ),
        }));
        return;
      }
      const newest = current.items[0];
      // 목록 앞쪽보다 늦게 바뀐 줄만 앞에 더한다. 모델만 바꾼 오래된 대화는 순서를 건드리지 않으므로 한 줄로만 둔다.
      if (
        newest === undefined ||
        Date.parse(conversation.updatedAt) >= Date.parse(newest.updatedAt)
      ) {
        commit((now) => ({ ...now, items: [conversation, ...now.items] }));
      } else {
        setPinned((now) => new Map(now).set(conversation.id, conversation));
      }
    },
    [commit],
  );

  const remove = useCallback(async (id: string) => {
    const response = await fetch(`/api/chat/conversations/${id}`, {
      method: "DELETE",
    });
    if (!response.ok) throw await failure(response);
  }, []);

  const drop = useCallback(
    (id: string) => {
      commit((current) => ({
        ...current,
        items: current.items.filter((item) => item.id !== id),
      }));
      setPinned((current) => {
        if (!current.has(id)) return current;
        const next = new Map(current);
        next.delete(id);
        return next;
      });
    },
    [commit],
  );

  const value = useMemo(
    () => ({
      conversations: page.items,
      loading,
      error,
      hasMore: page.nextCursor !== null,
      loadingMore,
      moreError,
      loadMore,
      wasPaged,
      pinned,
      pin,
      refresh,
      startNew,
      newConversationVersion,
      rename,
      replace,
      remove,
      drop,
    }),
    [
      page,
      loading,
      error,
      loadingMore,
      moreError,
      loadMore,
      wasPaged,
      pinned,
      pin,
      refresh,
      startNew,
      newConversationVersion,
      rename,
      replace,
      remove,
      drop,
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

/**
 * 대화 한 줄을 찾는다. 읽어 둔 목록에 없으면 한 줄로 읽어 온다.
 *
 * <p>목록은 첫 쪽만 읽으므로 오래된 대화를 주소로 열면 목록에 없다. 모델 칸과 에이전트 칸이 이 줄을 쓴다.
 */
export function useConversation(id: string | null): Conversation | undefined {
  const { conversations, loading, hasMore, pinned, pin } = useConversations();
  const listed =
    id === null ? undefined : conversations.find((item) => item.id === id);
  useEffect(() => {
    // 목록을 다 읽었는데 없으면 방금 만든 대화다. 곧 목록에 들어오므로 따로 읽지 않는다.
    if (id !== null && !loading && hasMore && listed === undefined)
      void pin(id);
  }, [id, loading, hasMore, listed, pin]);
  return listed ?? (id === null ? undefined : pinned.get(id));
}
