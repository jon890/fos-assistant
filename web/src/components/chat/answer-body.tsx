"use client";

import { useMemo, useSyncExternalStore } from "react";
import { recoverAnswers, splitAnswer, type AnswerSegment } from "@/lib/ask";
import { AskCard } from "./ask-card";
import { Markdown } from "./markdown";
import type { AnswerReveal, RevealFade, RevealSnapshot } from "./answer-reveal";
import type { TailFade } from "./reveal-fade";

const NOT_REVEALING: RevealSnapshot = { text: "", fades: [] };
const noSubscription = () => () => {};
const notRevealing = () => NOT_REVEALING;

/**
 * 에이전트의 답을 그린다. 본문은 마크다운으로, 답 끝의 `<ask>` 는 선택 카드로 그린다.
 *
 * 카드가 여럿이면 마지막 것만 누를 수 있다. 에이전트에게는 한 번만 두라고 알리지만 어겨도 답이 하나로 모인다.
 */
export function AnswerBody({
  content,
  reveal,
  streaming,
  nextUserMessage,
  onAnswer,
}: {
  /** 저장된 글이거나, 흐르는 답이면 지금까지 받은 글이다 */
  content: string;
  /** 흐르는 답이 받은 글을 풀어 보이는 장치다. 있으면 `content` 가 아니라 이 장치가 정한 글을 그린다 */
  reveal?: AnswerReveal;
  streaming: boolean;
  /** 바로 다음 메시지가 사용자 글일 때만 준다. 지난 카드의 답을 이 글에서 되찾는다 */
  nextUserMessage?: string;
  /** 이 답의 카드에 답할 수 있으면 준다. 없으면 카드는 읽기만 된다 */
  onAnswer?(text: string): void;
}) {
  const revealed = useSyncExternalStore(
    reveal ? reveal.subscribe : noSubscription,
    reveal ? reveal.getSnapshot : notRevealing,
    reveal ? reveal.getSnapshot : notRevealing,
  );
  const text = reveal ? revealed.text : content;
  const segments = useMemo(
    () => splitAnswer(text, streaming),
    [text, streaming],
  );
  const fades = reveal ? tailFades(text, segments, revealed.fades) : undefined;
  const lastAsk = segments.findLastIndex((segment) => segment.kind === "ask");
  return (
    <>
      {segments.map((segment, index) =>
        segment.kind === "markdown" ? (
          <Markdown
            key={index}
            fades={index === segments.length - 1 ? fades : undefined}
          >
            {segment.text}
          </Markdown>
        ) : (
          <AskCard
            key={index}
            ask={segment.ask}
            active={onAnswer !== undefined && index === lastAsk}
            answered={
              index === lastAsk && nextUserMessage !== undefined
                ? recoverAnswers(segment.ask, nextUserMessage)
                : null
            }
            onSubmit={(text) => onAnswer?.(text)}
          />
        ),
      )}
    </>
  );
}

/**
 * 새로 보인 구간을 마지막 마크다운 조각의 글 끝에서 센 거리로 바꾼다.
 * 마지막 조각이 글의 끝과 맞지 않으면(끝에 카드가 있거나 `<ask>` 앞부분을 가렸으면) 흐리게 그리지 않는다.
 */
function tailFades(
  text: string,
  segments: AnswerSegment[],
  fades: RevealFade[],
): TailFade[] | undefined {
  const last = segments.at(-1);
  if (
    fades.length === 0 ||
    last?.kind !== "markdown" ||
    !text.endsWith(last.text)
  )
    return undefined;
  return fades.map((fade) => ({
    startFromEnd: text.length - fade.from,
    endFromEnd: text.length - fade.to,
    progress: fade.progress,
  }));
}
