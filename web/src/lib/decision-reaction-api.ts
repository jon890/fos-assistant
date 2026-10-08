import { ATTENTION_CHANGED_EVENT } from "@/lib/attention-api";
import { memoryRequest } from "@/lib/memory-api";

export type DecisionReaction = "ACCEPTED" | "DISMISSED";

const NOT_FOUND_CODE = "AUTONOMY_DECISION_NOT_FOUND";
const REACT_FAILED = "반응을 남기지 못했어요. 잠시 뒤 다시 눌러 주세요.";

/** 먼저 다룰 문제 하나에 반응한다. 성공하면 본문 없이 끝나고 지금 화면의 건수를 다시 읽게 알린다. */
export async function reactToDecision(id: number, reaction: DecisionReaction) {
  const result = await memoryRequest<null>(
    `/api/autonomy-decisions/${id}/reaction`,
    REACT_FAILED,
    { method: "PUT", body: { reaction } },
  );
  if (result.ok) {
    window.dispatchEvent(new Event(ATTENTION_CHANGED_EVENT));
    return result;
  }
  // 판정이 없다는 거절만 그 코드의 문구를 쓰고, 나머지는 이 자리의 안내 하나로 알린다.
  return result.code === NOT_FOUND_CODE
    ? result
    : { ...result, message: REACT_FAILED };
}
