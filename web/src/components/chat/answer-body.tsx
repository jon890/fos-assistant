"use client";

import { useMemo } from "react";
import { recoverAnswers, splitAnswer } from "@/lib/ask";
import { AskCard } from "./ask-card";
import { Markdown } from "./markdown";

/**
 * 에이전트의 답을 그린다. 본문은 마크다운으로, 답 끝의 `<ask>` 는 선택 카드로 그린다.
 *
 * 카드가 여럿이면 마지막 것만 누를 수 있다. 에이전트에게는 한 번만 두라고 알리지만 어겨도 답이 하나로 모인다.
 */
export function AnswerBody({ content, streaming, nextUserMessage, onAnswer }: {
  content: string;
  streaming: boolean;
  /** 바로 다음 메시지가 사용자 글일 때만 준다. 지난 카드의 답을 이 글에서 되찾는다 */
  nextUserMessage?: string;
  /** 이 답의 카드에 답할 수 있으면 준다. 없으면 카드는 읽기만 된다 */
  onAnswer?(text: string): void;
}) {
  const segments = useMemo(() => splitAnswer(content, streaming), [content, streaming]);
  const lastAsk = segments.findLastIndex((segment) => segment.kind === "ask");
  return (
    <>
      {segments.map((segment, index) => segment.kind === "markdown"
        ? <Markdown key={index}>{segment.text}</Markdown>
        : <AskCard key={index} ask={segment.ask} active={onAnswer !== undefined && index === lastAsk}
            answered={index === lastAsk && nextUserMessage !== undefined
              ? recoverAnswers(segment.ask, nextUserMessage) : null}
            onSubmit={(text) => onAnswer?.(text)} />)}
    </>
  );
}
