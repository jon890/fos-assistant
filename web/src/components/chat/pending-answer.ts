import type { Dispatch, SetStateAction } from "react";
import { AnswerReveal } from "./answer-reveal.ts";
import type { Turn } from "./message-types";
import type { TurnStreamCallbacks } from "./conversation-session-types";

/** 흐르는 답 하나가 화면에 보이는 과정을 맡는 부품이다. */
export type PendingAnswer = Pick<
  TurnStreamCallbacks,
  "onDelta" | "onReset" | "onReplace"
> & {
  /** 마지막 조각이 왔다. 남은 글이 모두 보이면 이행한다. 저장된 메시지로 바꾸기 전에 기다린다. */
  finish(): Promise<void>;
};

/**
 * 보낸 turn, 다시 시도, 대화 단위 SSE 의 자동 turn 이 흐르는 답을 같은 방식으로 그리게 한다.
 *
 * 임시 답 turn 의 `content` 는 받은 글이고, 화면에 보이는 글은 turn 이 들고 있는 `reveal` 이 정한다.
 */
export function createPendingAnswer(
  setTurns: Dispatch<SetStateAction<Turn[]>>,
  pendingId: string,
): PendingAnswer {
  const reveal = new AnswerReveal();
  const remove = () =>
    setTurns((previous) => previous.filter((turn) => turn.id !== pendingId));
  /** 임시 답 turn 의 받은 글을 `change` 로 바꾸고, turn 이 없으면 만든다. */
  const write = (change: (received: string) => string) =>
    setTurns((previous) => {
      const current = previous.find((turn) => turn.id === pendingId);
      if (current === undefined)
        return [
          ...previous,
          {
            id: pendingId,
            role: "ASSISTANT",
            content: change(""),
            senderName: null,
            reveal,
          },
        ];
      return previous.map((turn) =>
        turn.id === pendingId
          ? { ...turn, content: change(turn.content) }
          : turn,
      );
    });
  return {
    onDelta: (text) => {
      reveal.receive(text);
      write((received) => received + text);
    },
    onReset: () => {
      // 막혀서 넘어간 시도의 조각이다. 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
      reveal.reset();
      remove();
    },
    onReplace: (text) => {
      if (text === "") {
        reveal.reset();
        remove();
        return;
      }
      reveal.replace(text);
      write(() => text);
    },
    finish: () => reveal.finish(),
  };
}
