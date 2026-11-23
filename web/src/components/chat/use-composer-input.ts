"use client";

import type { KeyboardEvent, RefObject } from "react";
import type { useComposerOutgoing } from "./use-composer-outgoing";
import { useLayoutEffect } from "react";
import {
  filterSkillNames,
  findSkillQuery,
  withSkillCommand,
} from "./skill-command";
import { Props } from "./composer-types";
import type { ComposerState } from "./use-composer-state";

type Context = { composing: RefObject<boolean> } & Pick<
  Props,
  "running" | "canQueue"
> &
  Pick<ReturnType<typeof useComposerOutgoing>, "trySend"> &
  Pick<
    ComposerState,
    | "textareaRef"
    | "pendingCaretRef"
    | "caret"
    | "skillDismissed"
    | "skillIndex"
    | "setSkillDismissed"
    | "setCaret"
    | "setSkillIndex"
  > &
  Pick<Props, "value" | "skillNames" | "onChange">;

export function useComposerInput({
  textareaRef,
  pendingCaretRef,
  caret,
  skillDismissed,
  skillIndex,
  setSkillDismissed,
  setCaret,
  setSkillIndex,
  value,
  skillNames,
  onChange,
  composing,
  running,
  canQueue,
  trySend,
}: Context) {
  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
  }, [value]);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    const nextCaret = pendingCaretRef.current;
    if (!textarea || nextCaret === null) return;
    pendingCaretRef.current = null;
    textarea.focus();
    textarea.setSelectionRange(nextCaret, nextCaret);
  }, [value]);

  const skillQuery =
    skillNames && !skillDismissed ? findSkillQuery(value, caret) : null;
  const skillMatches =
    skillNames && skillQuery !== null
      ? filterSkillNames(skillNames, skillQuery)
      : [];
  // 맞는 이름이 없으면 띄우지 않는다. `/usr/bin` 처럼 커맨드가 아닌 글을 칠 때 목록이 가리지 않게 한다.
  // 스킬이 없는 에이전트는 `/` 만 친 동안에만 스킬이 없다고 알린다.
  const skillMenuOpen =
    skillNames !== undefined &&
    skillQuery !== null &&
    (skillNames.length === 0 ? skillQuery === "" : skillMatches.length > 0);
  const activeSkillIndex = Math.min(
    skillIndex,
    Math.max(0, skillMatches.length - 1),
  );

  function changeValue(nextValue: string, nextCaret: number) {
    if (findSkillQuery(nextValue, nextCaret) === null) setSkillDismissed(false);
    setCaret(nextCaret);
    setSkillIndex(0);
    onChange(nextValue);
  }

  function pickSkill(name: string) {
    const next = withSkillCommand(value, name);
    pendingCaretRef.current = next.caret;
    changeValue(next.value, next.caret);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    const imeComposing = composing.current || event.nativeEvent.isComposing;
    if (skillMenuOpen && event.key === "Escape") {
      // 목록만 닫고 대화 화면의 중지나 패널 닫기로 넘기지 않는다.
      event.preventDefault();
      event.stopPropagation();
      setSkillDismissed(true);
      return;
    }
    if (skillMenuOpen && !imeComposing && skillMatches.length > 0) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        const step = event.key === "ArrowDown" ? 1 : -1;
        setSkillIndex(
          (activeSkillIndex + step + skillMatches.length) % skillMatches.length,
        );
        return;
      }
      if ((event.key === "Enter" && !event.shiftKey) || event.key === "Tab") {
        // 고를 이름이 있을 때만 Enter 가 고르기다. 스킬이 없다는 문구만 떠 있으면 평소처럼 보낸다.
        event.preventDefault();
        pickSkill(skillMatches[activeSkillIndex]!);
        return;
      }
    }
    if (
      event.key !== "Enter" ||
      event.shiftKey ||
      composing.current ||
      event.nativeEvent.isComposing ||
      (running && !canQueue)
    ) {
      return;
    }
    event.preventDefault();
    void trySend();
  }
  return {
    changeValue,
    pickSkill,
    skillQuery,
    skillMatches,
    skillMenuOpen,
    activeSkillIndex,
    handleKeyDown,
  };
}
export type ComposerInput = ReturnType<typeof useComposerInput>;
