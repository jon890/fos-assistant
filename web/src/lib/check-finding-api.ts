import { memoryRequest } from "@/lib/memory-api";

export type FindingReaction = "ACCEPTED" | "POSTPONED" | "DISMISSED";

/**
 * 살펴보기가 「새로 알릴 것」 으로 그린 발견 한 줄과 지금 반응이다.
 *
 * <p>`executionId` 는 그 살펴보기의 루트 실행 번호이고 답 메시지의 실행 번호와 같다.
 */
export type CheckFinding = {
  id: number;
  checkId: number;
  executionId: number;
  area: string;
  topicKey: string | null;
  title: string;
  reaction: FindingReaction | null;
};

/** `dismissWindowDays` 는 「관심 없음」 을 고른 주제를 다시 알리지 않는 날 수다. */
export type CheckFindings = {
  dismissWindowDays: number;
  findings: CheckFinding[];
};

const READ_FAILED = "발견을 읽지 못했어요.";
const REACT_FAILED = "반응을 남기지 못했어요. 잠시 뒤 다시 눌러 주세요.";

export const readCheckFindings = (conversationId: string) =>
  memoryRequest<CheckFindings>(
    `/api/chat/conversations/${conversationId}/check-findings`,
    READ_FAILED,
  );

/** 발견 하나에 반응한다. 성공하면 본문 없이 끝난다. */
export async function reactToFinding(id: number, reaction: FindingReaction) {
  const result = await memoryRequest<null>(
    `/api/check-findings/${id}/reaction`,
    REACT_FAILED,
    { method: "PUT", body: { reaction } },
  );
  // 서버가 준 다른 문구 대신 이 자리의 안내 하나로 알린다.
  return result.ok ? result : { ...result, message: REACT_FAILED };
}
