import { ATTENTION_CHANGED_EVENT } from "@/lib/attention-api";
import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";

/** 할 일 요청이다. 계약은 `backend/docs/flow.md` 의 「API(할 일)」 가 갖는다. */

/** 할 일 하나다. 제목은 모델이 쓴 글일 수 있어 평문으로만 그린다. */
export type FollowUpView = {
  id: string;
  title: string;
  status: "PROPOSED" | "OPEN" | "DONE" | "DROPPED" | "REJECTED";
  dueAt: string | null;
  waiting: boolean;
  conversationId: string | null;
  proposed: boolean;
  createdAt: string;
  acceptedAt: string | null;
  closedAt: string | null;
};

/** 할 일 폼의 값이다. `dueAt` 은 시간대가 붙은 ISO 시각이고 `null` 이면 기한이 없다. */
export type FollowUpInput = {
  title: string;
  dueAt: string | null;
  waiting: boolean;
};

const SAVE_FAILED = "할 일을 저장하지 못했어요. 다시 시도해 주세요.";

async function followUpRequest(
  path: string,
  init: { method: string; body?: unknown },
): Promise<MemoryApiResult<FollowUpView>> {
  const result = await memoryRequest<FollowUpView>(path, SAVE_FAILED, init);
  if (result.ok) window.dispatchEvent(new Event(ATTENTION_CHANGED_EVENT));
  return result;
}

/** 할 일을 직접 더한다. 바로 열린 할 일이 된다. */
export function createFollowUp(
  input: FollowUpInput,
): Promise<MemoryApiResult<FollowUpView>> {
  return followUpRequest("/api/follow-ups", { method: "POST", body: input });
}

/** 폼의 값을 그대로 보낸다. 기한 칸을 비웠으면 `dueAt: null` 이라 기한이 지워진다. */
export function updateFollowUp(
  id: string,
  patch: FollowUpInput,
): Promise<MemoryApiResult<FollowUpView>> {
  return followUpRequest(`/api/follow-ups/${id}`, {
    method: "PATCH",
    body: patch,
  });
}

export function acceptFollowUp(
  id: string,
): Promise<MemoryApiResult<FollowUpView>> {
  return followUpRequest(`/api/follow-ups/${id}/accept`, { method: "POST" });
}

export function rejectFollowUp(
  id: string,
): Promise<MemoryApiResult<FollowUpView>> {
  return followUpRequest(`/api/follow-ups/${id}/reject`, { method: "POST" });
}

/** 열린 할 일을 끝낸다(`done`). */
export function finishFollowUp(
  id: string,
): Promise<MemoryApiResult<FollowUpView>> {
  return followUpRequest(`/api/follow-ups/${id}/done`, { method: "POST" });
}

export function dropFollowUp(
  id: string,
): Promise<MemoryApiResult<FollowUpView>> {
  return followUpRequest(`/api/follow-ups/${id}/drop`, { method: "POST" });
}
