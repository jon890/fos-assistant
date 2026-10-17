/**
 * 기억 사이의 대체 제안이다. `MemoryDtos.SupersedeProposalView` 를 그대로 받는다.
 *
 * <p>새 기억이 옛 기억을 대신한다. 받아들이면 옛 기억은 다음 대화부터 쓰이지 않고 「대체됨」 으로 남는다.
 */
export type SupersedeProposal = {
  id: number;
  newMemory: { id: number; title: string };
  oldMemory: { id: number; title: string };
  origin: "AGENT" | "HUMAN" | "AUTO";
  createdAt: string;
};

export function fetchPendingSupersedes(): Promise<Response> {
  return fetch("/api/memory-links/pending", { cache: "no-store" });
}

export function decideMemoryLink(
  id: number,
  action: "accept" | "reject" | "revoke",
): Promise<Response> {
  return fetch(`/api/memory-links/${id}/${action}`, { method: "POST" });
}
