import { describeError } from "@/components/error-message";
import type { Turn } from "./message-bubble";
import type { ChatEvent } from "@/lib/chat-event";
import type { PendingResult } from "@/lib/pending-messages";
import type { ObservedTurnFilter } from "./conversation-session-types";

export async function readPayload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

/**
 * 마지막 질문 뒤에 새로 저장된 답을 찾는다.
 *
 * <p>Control Plane 은 답 행을 성공한 turn 과, 멈춘 자리까지의 답이 있는 중지에서만 저장한다. 실패한 turn 에는
 * 질문만 남는다(`ChatService` 의 `finish` 와 `cancel`). 그래서 마지막 질문 뒤의 답 행은 끝난 답이다. 다시
 * 생성은 이전 답이 이미 그 자리에 있으므로 보내기 전에 저장돼 있던 행은 뺀다.
 */
export function answerAfterLastQuestion(
  loaded: Turn[],
  savedBefore: ReadonlySet<Turn["id"]>,
): Turn | undefined {
  let answer: Turn | undefined;
  for (const turn of loaded) {
    if (turn.role === "USER") answer = undefined;
    // 알림 줄은 질문도 답도 아니다. 질문 뒤의 답을 찾을 때 건너뛴다.
    else if (turn.role === "ASSISTANT" && !savedBefore.has(turn.id))
      answer = turn;
  }
  return answer;
}

/**
 * 마지막 질문이 보내기 전에 없던 새 행인지다. `started` 를 받기 전에 끊겨도 서버가 질문을 저장했을 수 있다.
 * 그때 보낸 글을 입력창에 되돌리면 저장된 질문과 함께 보이고, 다시 보내면 두 번 저장된다.
 */
export function lastQuestionIsNew(
  loaded: Turn[],
  savedBefore: ReadonlySet<Turn["id"]>,
): boolean {
  const question = loaded.findLast((turn) => turn.role === "USER");
  return question !== undefined && !savedBefore.has(question.id);
}

/** 화면에 있는 저장된 메시지의 번호다. 끊긴 뒤 새로 저장된 답을 가려낼 때 쓴다. */
export function savedIdsOf(turns: Turn[]): Set<Turn["id"]> {
  return new Set(
    turns.filter((turn) => typeof turn.id === "number").map((turn) => turn.id),
  );
}

/**
 * 흐름이 오래 걸린다고 알리기까지 기다리는 시간이다.
 *
 * <p>실측한 포지션 추천 하나가 15분 넘게 걸렸고, 넷으로 나누면 더 걸릴 수도 있다. 2분이 지나면 한 번만
 * 알리고 그 뒤로는 다시 알리지 않는다.
 */
export const SLOW_FLOW_MS = 120_000;

/** 다른 창에서 도는 turn 과 그 실행 트리를 다시 묻는 주기다. */
export const OBSERVE_INTERVAL_MS = 3_000;

/** 도는 turn 조회가 이만큼 이어 실패하면 기다리는 표시를 거두고 이력을 다시 읽는다. */
export const OBSERVE_MAX_FAILURES = 3;

/** 대화 단위 SSE 가 끊긴 뒤 다시 열기까지 기다리는 시간이다. */
export const EVENTS_RECONNECT_MS = 5_000;

/**
 * 대기 메시지 요청의 실패를 문구로 바꾼다.
 *
 * <p>대기 경로의 `CONVERSATION_BUSY` 는 답을 만드는 중이라는 뜻이 아니다. 흐름이 붙은 에이전트라 대기 메시지를
 * 받지 않는다는 뜻이라 이 경로에서만 문구를 바꾼다. 요청이 닿지 못했으면 문구가 비어 오므로 `fallback` 을 쓴다.
 */
export function describePendingFailure(
  failure: Extract<PendingResult<unknown>, { ok: false }>,
  fallback: string,
): string {
  if (failure.code === "CONVERSATION_BUSY")
    return "이 대화는 답이 끝난 뒤 보낼 수 있어요.";
  return describeError(failure.code, failure.message || fallback);
}

/**
 * 보는 중인 turn 의 사건이면 참이다. 그 turn 은 폴링이 그리므로 대화 단위 SSE 로 받은 것은 버린다.
 *
 * <p>`filter` 의 단계를 이 자리에서 옮긴다. `system` 과 `approval` 은 turn 의 사건이 아니므로 언제나 받는다.
 */
export function belongsToObservedTurn(
  filter: ObservedTurnFilter | null,
  event: ChatEvent,
): boolean {
  if (
    filter === null ||
    filter.phase === "passed" ||
    event.type === "system" ||
    event.type === "approval"
  )
    return false;
  if (event.type === "started") {
    // 번호가 붙기 전에 보기 시작했으면 처음 받는 `started` 가 보는 turn 이다.
    if (
      filter.phase === "waiting" &&
      (filter.watchedId === null || event.executionId === filter.watchedId)
    ) {
      filter.phase = "skipping";
      return true;
    }
    filter.phase = "passed";
    return false;
  }
  if (event.type === "done" || event.type === "stopped")
    filter.phase = "passed";
  return true;
}
