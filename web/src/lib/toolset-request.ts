export type ToolsetRequest = {
  id: string;
  agentCode: string;
  agentName: string;
  agentDeleted: boolean;
  requesterName: string;
  toolset: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED" | "EXPIRED";
  reason: string | null;
  requestedAt: string;
  decidedAt: string | null;
};

export const requestStatusText: Record<ToolsetRequest["status"], string> = {
  PENDING: "요청 중",
  APPROVED: "승인됐어요",
  REJECTED: "거절됐어요",
  CANCELLED: "취소했어요",
  EXPIRED: "요청이 만료됐어요",
};

export function fetchToolsetRequests(code: string, admin: boolean) {
  return fetch(`/api/${admin ? "admin/" : ""}agents/${code}/tool-requests`);
}

export function requestToolset(code: string, toolset: string) {
  return fetch(`/api/agents/${code}/tool-requests`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ toolset }),
  });
}

export function cancelToolsetRequest(id: string) {
  return fetch(`/api/tool-requests/${id}/cancel`, { method: "POST" });
}

export function decideToolsetRequest(
  id: string,
  approve: boolean,
  reason: string,
) {
  return fetch(`/api/admin/tool-requests/${id}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ approve, reason: approve ? null : reason }),
  });
}

/** 요청 실패의 내부 원문은 화면에 내보내지 않는다. */
export async function readRequestResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(
      "도구 사용 요청을 처리하지 못했어요. 상태를 다시 확인해 주세요.",
    );
  }
  return (await response.json()) as T;
}
