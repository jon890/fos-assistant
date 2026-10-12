import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { isPublicId } from "@/lib/conversation-id";

/** 화면이 STALE·삭제·남의 것을 가려 안내하도록 그대로 넘기는 code 다. 그 밖의 code 는 `INTERNAL_ERROR` 로 바꾼다. */
const PASSED_CODES = new Set([
  "CONTENT_DRAFT_STALE",
  "CONTENT_DRAFT_GONE",
  "CONTENT_DRAFT_UNAVAILABLE",
  "CONTENT_DRAFT_JOB_UNAVAILABLE",
  "CONVERSATION_NOT_FOUND",
  "VALIDATION_FAILED",
  "UNAUTHENTICATED",
  "FORBIDDEN",
]);

/**
 * 원고 snapshot 과 job 을 읽는 GET 을 Control Plane 에 넘긴다.
 *
 * <p>경로와 cursor 의 ID 는 UUID 모양만 통과시킨다. 사용자는 세션이 정하므로 요청이 주인을 고를 수 없다.
 * Control Plane 의 오류 원문은 넘기지 않고 문구를 다시 정한다.
 */
export async function contentDraftGet(path: string, ids: string[]) {
  if (!ids.every(isPublicId))
    return errorResponse("VALIDATION_FAILED", "주소가 올바르지 않아요.", 400);
  const result = await callControlPlane<unknown>(path);
  if (result.ok)
    return NextResponse.json(result.data, { status: result.status });
  const code = PASSED_CODES.has(result.code) ? result.code : "INTERNAL_ERROR";
  return errorResponse(code, "원고를 불러오지 못했어요.", result.status);
}
