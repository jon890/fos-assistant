import { memoryRequest } from "@/lib/memory-api";

/**
 * 답을 만든 실행이 `memory_read` 로 본문을 읽은 기억 한 줄이다. `ChatDtos.MemoryUseView` 를 그대로 받는다.
 * 요약 구역처럼 조립이 넣은 기억은 오지 않는다.
 */
export type MemoryUse = {
  executionId: number;
  memoryId: number;
  title: string;
  scope: "USER" | "GROUP";
};

const READ_FAILED = "참고한 기억을 읽지 못했어요.";

export const readMemoryUses = (conversationId: string) =>
  memoryRequest<MemoryUse[]>(
    `/api/chat/conversations/${conversationId}/memory-uses`,
    READ_FAILED,
  );

/** 「참고한 기억」 항목의 단추다. `IRRELEVANT` 는 「관련 없음」, `WRONG` 은 「틀림」 이다. 기억 내용은 바뀌지 않는다. */
export type MemoryUseReaction = "IRRELEVANT" | "WRONG";

const REACT_FAILED = "반응을 남기지 못했어요. 잠시 뒤 다시 눌러 주세요.";

/** 답 하나가 참고한 기억 하나에 반응을 남긴다. 성공하면 본문 없이 끝난다. */
export const reactToMemoryUse = (
  conversationId: string,
  use: Pick<MemoryUse, "executionId" | "memoryId">,
  reaction: MemoryUseReaction,
) =>
  memoryRequest<null>(
    `/api/chat/conversations/${conversationId}/memory-uses/reactions`,
    REACT_FAILED,
    {
      method: "POST",
      body: { executionId: use.executionId, memoryId: use.memoryId, reaction },
    },
  );
