import { memoryRequest } from "@/lib/memory-api";

export type DecisionReaction = "ACCEPTED" | "DISMISSED";

const REACT_FAILED = "반응을 남기지 못했어요. 잠시 뒤 다시 눌러 주세요.";

/** 먼저 다룰 문제 하나에 반응한다. 성공하면 본문 없이 끝난다. */
export async function reactToDecision(id: number, reaction: DecisionReaction) {
  const result = await memoryRequest<null>(
    `/api/autonomy-decisions/${id}/reaction`,
    REACT_FAILED,
    { method: "PUT", body: { reaction } },
  );
  // 서버가 준 다른 문구 대신 이 자리의 안내 하나로 알린다.
  return result.ok ? result : { ...result, message: REACT_FAILED };
}
