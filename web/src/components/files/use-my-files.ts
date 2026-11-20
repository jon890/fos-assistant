"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { describeFailure } from "@/components/error-message";
import {
  fetchVaultItem,
  fetchVaultItems,
  VAULT_ERRORS,
  type VaultItem,
  type VaultKind,
  type VaultList,
} from "@/lib/artifact-vault-api";

export type Usage = Omit<VaultList, "items" | "nextCursor">;

export type FileListFilter = {
  kind: VaultKind | null;
  q: string;
  conversation: string | null;
};

const LOAD_FAILED = "내 파일을 불러오지 못했어요.";

/**
 * 「내 파일」 목록을 한 쪽씩 읽는다. 조건이 바뀌면 처음 쪽부터 다시 읽고, `loadMore` 는 다음 쪽을 뒤에 붙인다.
 *
 * <p>늦게 온 앞 조건의 응답은 버린다. 다음 쪽을 읽지 못하면 이미 읽은 줄은 그대로 두고 그 실패만 알린다.
 */
export function useMyFiles(filter: FileListFilter) {
  const [items, setItems] = useState<VaultItem[] | null>(null);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);
  const { kind, q, conversation } = filter;

  useEffect(() => {
    const current = ++generation.current;
    void (async () => {
      try {
        const response = await fetchVaultItems({ kind, q, conversation });
        if (current !== generation.current) return;
        if (!response.ok) {
          setFailure(await describeFailure(response, VAULT_ERRORS));
          return;
        }
        const page = (await response.json()) as VaultList;
        if (current !== generation.current) return;
        setItems(page.items);
        setNextCursor(page.nextCursor);
        setUsage(page);
        setFailure(null);
        setMoreFailed(false);
      } catch {
        if (current === generation.current) setFailure(LOAD_FAILED);
      }
    })();
  }, [kind, q, conversation, attempt]);

  const loadMore = useCallback(async () => {
    if (nextCursor === null || moreBusy) return;
    const current = generation.current;
    setMoreBusy(true);
    try {
      const response = await fetchVaultItems({
        kind,
        q,
        conversation,
        before: nextCursor,
      });
      if (current !== generation.current) return;
      if (!response.ok) {
        setMoreFailed(true);
        return;
      }
      const page = (await response.json()) as VaultList;
      if (current !== generation.current) return;
      setItems((previous) => [...(previous ?? []), ...page.items]);
      setNextCursor(page.nextCursor);
      setUsage(page);
      setMoreFailed(false);
    } catch {
      if (current === generation.current) setMoreFailed(true);
    } finally {
      setMoreBusy(false);
    }
  }, [kind, q, conversation, nextCursor, moreBusy]);

  /** 지운 줄을 빼고 용량만 다시 읽는다. 이미 읽은 쪽은 그대로 둔다. */
  const removed = useCallback(
    async (id: number) => {
      setItems(
        (previous) => previous?.filter((item) => item.id !== id) ?? null,
      );
      try {
        const response = await fetchVaultItems({ kind, q, conversation });
        if (response.ok) setUsage((await response.json()) as VaultList);
      } catch {
        // 용량 줄만 늦게 바뀐다. 다음에 읽을 때 맞춰진다.
      }
    },
    [kind, q, conversation],
  );

  return {
    items,
    usage,
    failure,
    nextCursor,
    hasMore: nextCursor !== null,
    moreBusy,
    moreFailed,
    loadMore,
    removed,
    retry: () => setAttempt((value) => value + 1),
  };
}

/** 대화의 「내 파일에서 보기」 가 가리킨 파일 하나를 읽어 미리보기로 연다. 첫 쪽에 없어도 연다. */
export function useLinkedItem(
  id: number,
  onFound: (item: VaultItem) => void,
  onFailed: (message: string) => void,
) {
  useEffect(() => {
    if (!Number.isInteger(id) || id <= 0) return;
    let active = true;
    void (async () => {
      try {
        const response = await fetchVaultItem(id);
        if (!active) return;
        if (response.ok) onFound((await response.json()) as VaultItem);
        else onFailed(await describeFailure(response, VAULT_ERRORS));
      } catch {
        if (active) onFailed("파일을 불러오지 못했어요.");
      }
    })();
    return () => {
      active = false;
    };
    // 주소의 번호가 바뀔 때만 다시 읽는다. 두 함수는 상태를 바꾸는 setter 다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);
}

/**
 * 목록 끝에 둔 요소가 화면에 들어오면 다음 쪽을 읽는다. 끝을 보는 기능이 없는 브라우저는 「더 보기」 단추를 쓴다.
 *
 * <p>쪽을 읽을 때마다(`cursor` 가 바뀔 때마다) 감시를 다시 건다. 읽은 뒤에도 끝이 화면 안에 있으면 그대로 이어 읽는다.
 */
export function useEndSentinel(
  cursor: number | null,
  loadMore: () => Promise<void>,
) {
  const target = useRef<HTMLDivElement>(null);
  const latest = useRef(loadMore);
  useEffect(() => {
    latest.current = loadMore;
  }, [loadMore]);
  useEffect(() => {
    const element = target.current;
    if (element === null || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void latest.current();
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [cursor]);
  return target;
}
