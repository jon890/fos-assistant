"use client";

import type { KeyboardEvent, RefObject } from "react";
import type { ComposerAttachments } from "./use-composer-attachments";
import { useLayoutEffect } from "react";
import { filterAgents, findMention } from "./agent-mention";
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
  Pick<ComposerAttachments, "trySend"> &
  Pick<
    ComposerState,
    | "textareaRef"
    | "pendingCaretRef"
    | "caret"
    | "dismissedMentionStart"
    | "mentionIndex"
    | "skillDismissed"
    | "skillIndex"
    | "setDismissedMentionStart"
    | "setSkillDismissed"
    | "setCaret"
    | "setMentionIndex"
    | "setSkillIndex"
  > &
  Pick<Props, "value" | "mention" | "skillNames" | "onChange">;

export function useComposerInput({
  textareaRef,
  pendingCaretRef,
  caret,
  dismissedMentionStart,
  mentionIndex,
  skillDismissed,
  skillIndex,
  setDismissedMentionStart,
  setSkillDismissed,
  setCaret,
  setMentionIndex,
  setSkillIndex,
  value,
  mention,
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

  const found = mention ? findMention(value, caret) : null;
  const openMention =
    found !== null && found.start !== dismissedMentionStart ? found : null;
  const mentionMatches =
    mention && openMention
      ? filterAgents(mention.agents, openMention.query)
      : [];
  const activeMentionIndex = Math.min(
    mentionIndex,
    Math.max(0, mentionMatches.length - 1),
  );
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
    const next = mention ? findMention(nextValue, nextCaret) : null;
    if (next === null || next.start !== dismissedMentionStart)
      setDismissedMentionStart(null);
    if (findSkillQuery(nextValue, nextCaret) === null) setSkillDismissed(false);
    setCaret(nextCaret);
    setMentionIndex(0);
    setSkillIndex(0);
    onChange(nextValue);
  }

  function pickSkill(name: string) {
    const next = withSkillCommand(value, name);
    pendingCaretRef.current = next.caret;
    changeValue(next.value, next.caret);
  }

  function pickMention(code: string) {
    if (!mention || !openMention) return;
    const end = textareaRef.current?.selectionStart ?? caret;
    pendingCaretRef.current = openMention.start;
    mention.onPick(code);
    changeValue(
      value.slice(0, openMention.start) + value.slice(end),
      openMention.start,
    );
  }
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    const imeComposing = composing.current || event.nativeEvent.isComposing;
    if (skillMenuOpen && event.key === "Escape") {
      // `@` 목록과 같다. 목록만 닫고 대화 화면의 중지나 패널 닫기로 넘기지 않는다.
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
    if (openMention && event.key === "Escape") {
      // 대화 화면의 Esc 처리기가 이 사건을 건너뛰게 한다. 목록만 닫고 중지나 패널 닫기로 넘기지 않는다.
      // 한글 조합 중에도 같다. 조합 중이라고 넘기면 목록이 떠 있는데 패널이 닫힌다.
      event.preventDefault();
      event.stopPropagation();
      setDismissedMentionStart(openMention.start);
      return;
    }
    if (openMention && !imeComposing) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        if (mentionMatches.length > 0) {
          const step = event.key === "ArrowDown" ? 1 : -1;
          setMentionIndex(
            (activeMentionIndex + step + mentionMatches.length) %
              mentionMatches.length,
          );
        }
        return;
      }
      if (
        (event.key === "Enter" && !event.shiftKey) ||
        (event.key === "Tab" && mentionMatches.length > 0)
      ) {
        // 목록이 떠 있는 동안의 Enter 는 고르기다. 맞는 것이 없어도 보내지 않는다.
        // Shift+Enter 는 고르지 않고 평소처럼 줄을 바꾼다. 줄이 바뀌면 `@` 뒤에 공백이 생겨 목록이 닫힌다.
        event.preventDefault();
        const picked = mentionMatches[activeMentionIndex];
        if (picked) pickMention(picked.code);
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
    pickMention,
    openMention,
    mentionMatches,
    activeMentionIndex,
    skillQuery,
    skillMatches,
    skillMenuOpen,
    activeSkillIndex,
    handleKeyDown,
  };
}
export type ComposerInput = ReturnType<typeof useComposerInput>;
