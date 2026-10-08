import { memoryRequest } from "@/lib/memory-api";

/**
 * 답을 만든 실행이 본문을 받은 기억 한 줄이다. `ChatDtos.MemoryUseView` 를 그대로 받는다.
 *
 * <p>`via` 는 받은 길이다. `ALWAYS` 는 항상 층, `FACTS` 는 개인 사실 구역, `READ` 는 `memory_read` 로 읽은 것이다.
 */
export type MemoryUse = {
  executionId: number;
  memoryId: number;
  title: string;
  scope: "USER" | "GROUP";
  via: "ALWAYS" | "FACTS" | "READ";
};

const READ_FAILED = "참고한 기억을 읽지 못했어요.";

export const readMemoryUses = (conversationId: string) =>
  memoryRequest<MemoryUse[]>(
    `/api/chat/conversations/${conversationId}/memory-uses`,
    READ_FAILED,
  );
