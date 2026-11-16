import type { ChatEvent, SteerInfo } from "@/lib/chat-event";
import type { Turn } from "./message-types";

/**
 * 도는 실행에 끼워 넣은 사용자 글(steer 말풍선)을 화면에 놓는 규칙이다(ADR-20261116 / run-steer).
 *
 * <p>말풍선은 그 turn 의 답 바로 위에 두고 답을 쪼개지 않는다. 이력은 행 번호 순서라 말풍선 행이 답 행보다 앞에
 * 오고, 도는 동안에는 `steer` 사건으로 흐르는 답 앞에 끼운다.
 */

/** 상태마다 말풍선 아래에 보이는 글이다. 「전달됨」 은 Hermes 가 받아 둔 것이지 모델이 읽었다는 뜻이 아니다 */
export const STEER_STATE_TEXT: Record<SteerInfo["state"], string> = {
  DELIVERED: "전달됨",
  CARRIED_OVER: "다음 차례로 넘어감",
  UNCONFIRMED: "전달됐는지 확인 못 함",
  NOT_DELIVERED: "전달 안 됨",
};

/** 받은 순간 돈 자식의 종류마다 말풍선 아래에 붙는 안내 한 줄이다 */
export const STEER_CHILD_NOTICE: Record<
  NonNullable<SteerInfo["child"]>,
  string
> = {
  SUBAGENT: "도우미가 하던 일은 바꾸지 못하고, 도우미가 끝난 뒤 반영돼요",
  DELEGATION: "맡긴 도우미가 하던 일은 바꾸지 못해요",
};

/** 아직 이력을 다시 읽지 않은 말풍선의 임시 id 다. 저장되기 전의 줄처럼 흐르는 답과 함께 뒤에 그린다 */
export function steerTurnId(messageId: number): string {
  return `steer-${messageId}`;
}

/** 이 줄이 steer 말풍선인지 본다. */
export function isSteerTurn(turn: Pick<Turn, "steer">): boolean {
  return turn.steer !== undefined && turn.steer !== null;
}

/**
 * `steer` 사건 하나를 대화 줄에 반영한다.
 *
 * <p>같은 번호의 말풍선이 있으면 상태만 바꾼다. 없으면 그 실행의 저장된 답이 있을 때 저장된 줄로 넣어 이력과 같은
 * 순서로 접히게 하고, 흐르는 답이 있으면 그 바로 앞에, 아직 답이 없으면 끝에 임시 id 로 넣는다. 끝에 넣은 말풍선
 * 뒤에 흐르는 답이 생기면 그 답이 아래에 붙는다.
 */
export function applySteerEvent(turns: Turn[], event: ChatEvent): Turn[] {
  if (event.type !== "steer" || event.messageId == null || !event.steer)
    return turns;
  const messageId = event.messageId;
  const liveId = steerTurnId(messageId);
  const steer = event.steer;
  const same = (turn: Turn) => turn.id === messageId || turn.id === liveId;
  if (turns.some(same))
    return turns.map((turn) => (same(turn) ? { ...turn, steer } : turn));
  const line = (id: Turn["id"]): Turn => ({
    id,
    role: "USER",
    content: event.text ?? "",
    senderName: null,
    steer,
  });
  const savedAnswer =
    event.executionId == null
      ? -1
      : turns.findIndex(
          (turn) =>
            typeof turn.id === "number" &&
            turn.role === "ASSISTANT" &&
            turn.executionId === event.executionId,
        );
  if (savedAnswer >= 0)
    return [
      ...turns.slice(0, savedAnswer),
      line(messageId),
      ...turns.slice(savedAnswer),
    ];
  const streaming = turns.findIndex(
    (turn) => typeof turn.id === "string" && turn.role === "ASSISTANT",
  );
  if (streaming >= 0)
    return [
      ...turns.slice(0, streaming),
      line(liveId),
      ...turns.slice(streaming),
    ];
  return [...turns, line(liveId)];
}
