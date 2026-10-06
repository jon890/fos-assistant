import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";

/**
 * 에이전트가 대화에서 남긴 기억 기록 한 줄이다. `ChatDtos.MemoryCaptureView` 를 그대로 받는다.
 *
 * <p>`status` 는 항목의 지금 승인 상태다. 제안(`PROPOSED`)을 받아들이면 `kind` 는 그대로 두고 `ACCEPTED` 가 된다.
 */
export type MemoryCapture = {
  id: number;
  memoryId: number;
  executionId: number;
  kind: "CREATED" | "UPDATED" | "PROPOSED";
  status: "ACCEPTED" | "PROPOSED" | "REJECTED";
  title: string;
  content: string;
  sensitive: boolean;
  alwaysInject: boolean;
  createdAt: string;
};

const READ_FAILED = "기억 기록을 읽지 못했어요.";
const ACT_FAILED = "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
const UNDO_CONFLICT =
  "그 뒤에 바뀌어 되돌릴 수 없어요. 기억 화면에서 고쳐 주세요.";

export const readMemoryCaptures = (conversationId: string) =>
  memoryRequest<MemoryCapture[]>(
    `/api/chat/conversations/${conversationId}/memory-captures`,
    READ_FAILED,
  );

/** 되돌린다. 그 뒤에 바뀐 항목은 되돌릴 수 없다는 안내를 준다. */
export async function undoMemoryCapture(
  id: number,
): Promise<MemoryApiResult<null>> {
  const result = await memoryRequest<null>(
    `/api/memory-captures/${id}/undo`,
    ACT_FAILED,
    { method: "POST" },
  );
  return !result.ok && result.code === "MEMORY_REVISION_CONFLICT"
    ? { ...result, message: UNDO_CONFLICT }
    : result;
}

export const editMemory = (
  memoryId: number,
  input: { content: string; alwaysInject: boolean },
) =>
  memoryRequest<unknown>(`/api/memories/${memoryId}`, ACT_FAILED, {
    method: "PATCH",
    body: input,
  });

export const decideMemory = (memoryId: number, action: "accept" | "reject") =>
  memoryRequest<unknown>(`/api/memories/${memoryId}/${action}`, ACT_FAILED, {
    method: "POST",
  });
