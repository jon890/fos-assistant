import type { UsageExecution } from "../components/usage/execution-list";

/** 실행 기록 목록의 한 쪽이다. */
export type UsageExecutionPage = {
  items: UsageExecution[];
  nextCursor: string | null;
};

/** 커서 다음 실행 기록을 읽는다. */
export function fetchUsageExecutionPage(
  cursor: string | null,
  limit = 50,
): Promise<Response> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor !== null) query.set("cursor", cursor);
  return fetch(`/api/usage/executions/page?${query}`, { cache: "no-store" });
}

/** 이미 읽은 줄을 유지하면서 다음 쪽의 같은 실행 번호는 한 번만 보인다. */
export function mergeUsageExecutionPage(
  current: UsageExecutionPage,
  next: UsageExecutionPage,
): UsageExecutionPage {
  const ids = new Set(current.items.map((execution) => execution.id));
  return {
    items: [
      ...current.items,
      ...next.items.filter((execution) => {
        if (ids.has(execution.id)) return false;
        ids.add(execution.id);
        return true;
      }),
    ],
    nextCursor: next.nextCursor,
  };
}
