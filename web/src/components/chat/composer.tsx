"use client";

import { useEffect, useRef } from "react";
import { ArrowUp, ImagePlus, LoaderCircle, Square, X } from "lucide-react";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { cn } from "cn";
import { attachmentPlaceholder } from "./variants";
import { AgentMention, mentionOptionId } from "./agent-mention";
import { SkillCommandMenu } from "./skill-command-menu";
import { skillOptionId } from "./skill-command";
import { ModelPicker, ModelTierPicker } from "./model-picker";
import { Props } from "./composer-types";
import { ACCEPTED_TYPES } from "./composer-attachment-utils";
import { useComposerState } from "./use-composer-state";
import { useComposerModel } from "./use-composer-model";
import { useComposerAttachments } from "./use-composer-attachments";
import { useComposerInput } from "./use-composer-input";
import { useComposerOutgoing } from "./use-composer-outgoing";
import { useOutgoingNavigation } from "./use-outgoing-navigation";

export function Composer(props: Props) {
  const composing = useRef(false);
  const state = useComposerState(props);
  const {
    onBlockingChange,
    disabled,
    running,
    mention,
    skillNames,
    value,
    canQueue,
    acceptsAttachments,
    canStop,
    onStop,
    agentCode,
    modelSelectionMode,
    modelTier,
    modelChoiceUnknown,
    modelChoice,
  } = props;
  const {
    blocking,
    items,
    pickNotice,
    mentionListId,
    skillListId,
    textareaRef,
    setCaret,
    fileInputRef,
    sendDisabled,
    outgoing,
  } = state;
  const composerModel = useComposerModel({ ...state, ...props });
  const { saveModelChoice, saveModelTier } = composerModel;
  const composerAttachments = useComposerAttachments({
    ...state,
    ...props,
    ...composerModel,
  });
  const { handleFiles, removeItem } = composerAttachments;
  const { trySend } = useComposerOutgoing({
    ...state,
    ...props,
    ...composerAttachments,
  });
  useOutgoingNavigation(outgoing !== null);
  const composerInput = useComposerInput({
    composing,
    ...state,
    ...props,
    ...composerModel,
    ...composerAttachments,
    trySend,
  });
  const {
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
  } = composerInput;

  useEffect(() => {
    onBlockingChange?.(blocking);
  }, [blocking, onBlockingChange]);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void trySend();
      }}
      className="mx-auto w-full max-w-3xl pt-3"
    >
      {items.length > 0 ? (
        <div
          data-testid="attachment-previews"
          className="mb-2 flex items-center gap-2 overflow-x-auto pb-1"
        >
          {items.map((item) => (
            <div key={item.key} className="relative shrink-0">
              {item.status === "error" ? (
                <div className={attachmentPlaceholder({ size: "preview" })}>
                  <span>{item.errorMessage}</span>
                </div>
              ) : (
                <div className="relative h-16 w-16 overflow-hidden rounded-md border border-border bg-muted">
                  {item.previewUrl ? (
                    // eslint 설정이 없는 저장소라 next/image 대신 object URL 을 바로 그린다.
                    <img
                      src={item.previewUrl}
                      alt=""
                      className="h-full w-full object-cover"
                    />
                  ) : null}
                  {item.status === "uploading" ? (
                    <span
                      data-testid="attachment-uploading"
                      aria-hidden="true"
                      className="absolute inset-0 flex items-center justify-center bg-background/60"
                    >
                      <LoaderCircle className="size-4 animate-spin text-foreground motion-reduce:animate-none" />
                    </span>
                  ) : null}
                </div>
              )}
              <TooltipButton
                label="사진 지우기"
                variant="outline"
                size="icon-xs"
                onClick={() => removeItem(item.key)}
                // 보내는 동안 지우면 막 메시지에 묶인 첨부에 DELETE 가 간다.
                disabled={disabled || running}
                className="absolute -right-1.5 -top-1.5 size-5 rounded-full bg-background"
              >
                <X aria-hidden="true" />
              </TooltipButton>
            </div>
          ))}
        </div>
      ) : null}

      {pickNotice ? (
        <p
          data-testid="attachment-notice"
          className="mb-2 text-xs text-muted-foreground"
        >
          {pickNotice}
        </p>
      ) : null}

      <div
        data-testid="composer-shell"
        className={cn(
          "relative flex items-end gap-2 p-1.5 pl-4",
          "rounded-2xl border border-input bg-card focus-within:border-ring",
        )}
      >
        {mention && openMention ? (
          <AgentMention
            id={mentionListId}
            agents={mention.agents}
            query={openMention.query}
            activeIndex={activeMentionIndex}
            onPick={pickMention}
          />
        ) : null}
        {skillNames && skillMenuOpen && skillQuery !== null ? (
          <SkillCommandMenu
            id={skillListId}
            names={skillNames}
            query={skillQuery}
            activeIndex={activeSkillIndex}
            onPick={pickSkill}
          />
        ) : null}
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          aria-label="메시지"
          aria-controls={
            openMention
              ? mentionListId
              : skillMenuOpen
                ? skillListId
                : undefined
          }
          aria-activedescendant={
            openMention && mentionMatches.length > 0
              ? mentionOptionId(mentionListId, activeMentionIndex)
              : skillMenuOpen && skillMatches.length > 0
                ? skillOptionId(skillListId, activeSkillIndex)
                : undefined
          }
          onChange={(event) =>
            changeValue(event.target.value, event.target.selectionStart)
          }
          onSelect={(event) => setCaret(event.currentTarget.selectionStart)}
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={() => {
            composing.current = false;
          }}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          placeholder="무엇이든 물어보세요"
          className={cn(
            "max-h-[7.5rem] min-h-10 flex-1 resize-none overflow-y-auto py-2",
            "bg-transparent text-base leading-6 outline-none disabled:opacity-50",
          )}
        />
        {acceptsAttachments ? (
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept={ACCEPTED_TYPES.join(",")}
              multiple
              hidden
              disabled={disabled || running || outgoing !== null}
              data-testid="attachment-input"
              onChange={(event) => void handleFiles(event)}
            />
            <TooltipButton
              label="사진 첨부"
              variant="outline"
              size="icon"
              onClick={() => fileInputRef.current?.click()}
              disabled={disabled || running || outgoing !== null}
              className="size-10 shrink-0 rounded-full"
            >
              <ImagePlus aria-hidden="true" className="size-5" />
            </TooltipButton>
          </>
        ) : null}
        {running ? (
          <TooltipButton
            label="중지"
            passEscape
            variant="outline"
            disabled={!canStop}
            onClick={onStop}
            size="icon"
            className="size-10 shrink-0 rounded-full"
          >
            <Square aria-hidden="true" className="size-4 fill-current" />
          </TooltipButton>
        ) : null}
        <TooltipButton
          label="보내기"
          variant="default"
          type="submit"
          disabled={sendDisabled || (running && !canQueue)}
          size="icon"
          className="size-10 shrink-0 rounded-full"
        >
          <ArrowUp aria-hidden="true" className="size-5" />
        </TooltipButton>
      </div>
      {/* 알약 안에 두면 좁은 폭에서 입력칸이 줄어든다. 그래서 알약 아래 줄에 둔다. */}
      <div className="mt-1 flex min-w-0 px-2">
        <div className="flex min-w-0 flex-col gap-1">
          <ModelTierPicker
            agentCode={agentCode}
            mode={modelSelectionMode}
            tier={modelTier}
            onChange={saveModelTier}
            disabled={disabled || agentCode.length === 0 || modelChoiceUnknown}
            advancedPicker={
              <ModelPicker
                agentCode={agentCode}
                choice={modelChoice}
                onChange={saveModelChoice}
                disabled={
                  disabled || agentCode.length === 0 || modelChoiceUnknown
                }
                inSettings
              />
            }
          />
        </div>
      </div>
    </form>
  );
}
