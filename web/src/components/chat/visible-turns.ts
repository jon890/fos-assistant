import {
  foldVersions,
  isLatestView,
  type VersionSlot,
} from "../../lib/message-versions.ts";
import type { Turn } from "./message-types";
import { isSteerTurn } from "./steer.ts";

/** 대화 목록에 그릴 줄 하나와 그 줄에 붙는 판 정보다. */
export type VisibleTurn = {
  turn: Turn;
  userVersion?: VersionSlot;
  answerVersion?: VersionSlot;
  /** 알림 줄이 연 turn 의 답이다. 다시 만들 질문이 없어 다시 생성 단추를 두지 않는다 */
  afterSystem?: boolean;
  /** 이 답 앞에 steer 말풍선이 있다. 서버가 다시 생성을 거절하므로 단추를 두지 않는다(ADR-20261116 / run-steer) */
  afterSteer?: boolean;
};

/**
 * 저장된 줄을 판으로 접고 아직 저장되지 않은 줄을 뒤에 붙여 그릴 순서를 정한다.
 *
 * <p>한 turn 안에서 steer 말풍선과 답은 행 번호로 섞는다. 답의 순서는 그 판 사슬의 마지막 행 번호로 정해 어느 판을
 * 고르든 말풍선이 같은 답 위에 남는다. 다시 생성하는 중이면 마지막 답을 숨긴다. 그 자리는 새로 흐르는 답이 채운다.
 */
export function visibleTurns(
  turns: Turn[],
  selectedVersions: Record<number, number>,
): { visible: VisibleTurn[]; latestView: boolean } {
  const persisted = turns
    .filter(
      (turn): turn is Turn & { id: number } => typeof turn.id === "number",
    )
    .map((turn) => ({
      ...turn,
      replacesMessageId: turn.replacesMessageId ?? null,
    }));
  const folded = foldVersions(persisted, selectedVersions);
  const unsaved = turns.filter(
    (turn): turn is Turn & { id: string } => typeof turn.id === "string",
  );
  const regenerating = unsaved.some((turn) =>
    turn.id.startsWith("assistant-regenerate-"),
  );
  const foldedVisible = folded.flatMap((fold, turnIndex): VisibleTurn[] => {
    const lastFold = turnIndex === folded.length - 1;
    const answers = fold.answers
      .filter(
        (_answer, answerIndex) =>
          !(
            regenerating &&
            lastFold &&
            answerIndex === fold.answers.length - 1
          ),
      )
      .map((answer) => ({
        order: answer.latestId,
        line: {
          turn: answer.message,
          answerVersion: answer.version,
          afterSystem: fold.user.role === "SYSTEM",
          afterSteer: fold.steers.some((steer) => steer.id < answer.latestId),
        } satisfies VisibleTurn,
      }));
    const steers = fold.steers.map((steer) => ({
      order: steer.id,
      line: { turn: steer } satisfies VisibleTurn,
    }));
    return [
      {
        turn: fold.user,
        userVersion: fold.user.role === "SYSTEM" ? undefined : fold.userVersion,
      },
      ...[...steers, ...answers]
        .sort((left, right) => left.order - right.order)
        .map(({ line }) => line),
    ];
  });
  let steeredUnsaved = false;
  const pendingVisible = unsaved.map((turn): VisibleTurn => {
    if (turn.role === "USER") steeredUnsaved = isSteerTurn(turn);
    return turn.role === "ASSISTANT" && steeredUnsaved
      ? { turn, afterSteer: true }
      : { turn };
  });
  return {
    visible: [...foldedVisible, ...pendingVisible],
    latestView: isLatestView(folded),
  };
}
