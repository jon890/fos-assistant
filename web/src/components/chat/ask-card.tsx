"use client";

import { useId, useState } from "react";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatAnswers, type Ask, type AskQuestion } from "@/lib/ask";

/** 선택지 대신 직접 적는 자리를 가리키는 값이다. 에이전트가 같은 이름의 선택지를 내도 겹치지 않게 글자가 아닌 값을 쓴다 */
const CUSTOM = Symbol("custom");
type Choice = string | typeof CUSTOM;

/**
 * 에이전트가 답 끝에 둔 질문을 선택 카드로 그린다.
 *
 * 마지막 답의 카드만 누를 수 있다. 지난 카드의 답은 다음 사용자 메시지에서 되찾아 보여 준다.
 * 고른 답은 평범한 사용자 메시지로 보낸다. 실행이 멈춰 기다리지 않으므로, 답하지 않고 다른 글을 보내도 된다.
 */
export function AskCard({
  ask,
  active,
  answered,
  onSubmit,
}: {
  ask: Ask;
  active: boolean;
  answered: string[][] | null;
  onSubmit(text: string): void;
}) {
  const [choices, setChoices] = useState<Choice[][]>(() =>
    ask.questions.map(() => []),
  );
  const [custom, setCustom] = useState<string[]>(() =>
    ask.questions.map(() => ""),
  );
  // 보내는 동안은 돌고 있는 turn 이 있어 `active` 가 꺼진다. 따로 「보냈다」 상태를 두지 않는다.
  // 두면 전송이 실패해 이 답이 다시 마지막이 되었을 때 카드가 꺼진 채 남는다.
  const enabled = active;

  const answers = ask.questions.map((question, index) =>
    answerOf(question, choices[index], custom[index]),
  );
  const complete = answers.every((answer) => answer.length > 0);

  function toggle(index: number, choice: Choice) {
    setChoices((previous) =>
      previous.map((current, at) => {
        if (at !== index) return current;
        if (!ask.questions[index].multiple) return [choice];
        return current.includes(choice)
          ? current.filter((value) => value !== choice)
          : [...current, choice];
      }),
    );
  }

  function submit() {
    if (!enabled || !complete) return;
    onSubmit(formatAnswers(ask, answers));
  }

  return (
    <form
      data-testid="ask-card"
      className="my-3 flex flex-col gap-4 rounded-lg border border-border bg-muted/40 p-4"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      {ask.questions.map((question, index) => {
        const restored = active ? null : answered?.[index];
        const typed =
          restored?.find(
            (value) =>
              !question.options.some((option) => option.label === value),
          ) ?? "";
        const restoredChoices: Choice[] =
          restored?.filter((value) =>
            question.options.some((option) => option.label === value),
          ) ?? [];
        if (typed) restoredChoices.push(CUSTOM);
        const chosen = active ? choices[index] : restoredChoices;
        return (
          <QuestionField
            key={index}
            question={question}
            enabled={enabled}
            chosen={chosen}
            customText={active ? custom[index] : typed}
            onToggle={(choice) => toggle(index, choice)}
            onCustomChange={(text) =>
              setCustom((previous) =>
                previous.map((value, at) => (at === index ? text : value)),
              )
            }
          />
        );
      })}
      {active ? (
        <div className="flex justify-end">
          <Button
            type="submit"
            size="sm"
            disabled={!enabled || !complete}
            data-testid="ask-submit"
          >
            답 보내기
          </Button>
        </div>
      ) : null}
    </form>
  );
}

function QuestionField({
  question,
  enabled,
  chosen,
  customText,
  onToggle,
  onCustomChange,
}: {
  question: AskQuestion;
  enabled: boolean;
  chosen: Choice[];
  customText: string;
  onToggle(choice: Choice): void;
  onCustomChange(text: string): void;
}) {
  const name = useId();
  const type = question.multiple ? "checkbox" : "radio";
  // 선택지가 없으면 직접 적는 것만 받는다. 있으면 「직접 입력」 을 골랐을 때만 칸을 연다.
  const freeOnly = question.options.length === 0;
  const customOpen = freeOnly || chosen.includes(CUSTOM);

  return (
    <fieldset className="flex min-w-0 flex-col gap-2" disabled={!enabled}>
      <legend className="mb-1 text-sm">
        {question.header ? (
          <span className="mr-2 text-xs font-medium text-muted-foreground">
            {question.header}
          </span>
        ) : null}
        <span className="font-medium">{question.text}</span>
        {question.multiple ? (
          <span className="ml-2 text-xs text-muted-foreground">
            여러 개를 고를 수 있어요
          </span>
        ) : null}
      </legend>
      {question.options.map((option) => (
        <label
          key={option.label}
          data-testid="ask-option"
          className={cn(
            "flex cursor-pointer items-start gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm",
            "has-[:checked]:border-primary has-[:disabled]:cursor-default",
          )}
        >
          <input
            type={type}
            name={name}
            className="mt-1"
            checked={chosen.includes(option.label)}
            onChange={() => onToggle(option.label)}
          />
          <span className="min-w-0">
            <span className="block break-words">{option.label}</span>
            {option.description ? (
              <span className="block break-words text-xs text-muted-foreground">
                {option.description}
              </span>
            ) : null}
          </span>
        </label>
      ))}
      {freeOnly ? null : (
        <label className="flex cursor-pointer items-center gap-2 px-3 text-sm text-muted-foreground has-[:disabled]:cursor-default">
          <input
            type={type}
            name={name}
            checked={chosen.includes(CUSTOM)}
            onChange={() => onToggle(CUSTOM)}
            data-testid="ask-custom-choice"
          />
          직접 입력
        </label>
      )}
      {customOpen &&
        (enabled ? (
          <Input
            aria-label={`${question.header ?? question.text} 직접 입력`}
            data-testid="ask-custom-input"
            value={customText}
            onChange={(event) => onCustomChange(event.target.value)}
          />
        ) : customText ? (
          <p className="break-words rounded-md border border-border bg-background px-3 py-2 text-sm">
            {customText}
          </p>
        ) : null)}
    </fieldset>
  );
}

function answerOf(
  question: AskQuestion,
  chosen: Choice[],
  customText: string,
): string[] {
  const picked = question.options
    .map((option) => option.label)
    .filter((label) => chosen.includes(label));
  const typed = customText.trim();
  const customWanted = question.options.length === 0 || chosen.includes(CUSTOM);
  // 직접 입력을 골라 놓고 비워 두면 그 질문은 아직 답하지 않은 것이다. 다른 선택지만 보내면 입력하려던 것이 빠진다.
  if (customWanted && typed.length === 0) return [];
  return customWanted ? [...picked, typed] : picked;
}
