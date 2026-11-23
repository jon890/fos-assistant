"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowUp, LoaderCircle, Plus, Square, X } from "lucide-react";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { cn } from "cn";
import { attachmentPlaceholder } from "./variants";
import { ReplyPreview } from "./reply-box";
import { SkillCommandMenu } from "./skill-command-menu";
import { skillOptionId } from "./skill-command";
import { ModelTierPicker } from "./model-picker";
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
  const [tierFailed, setTierFailed] = useState(false);
  const state = useComposerState(props);
  const {
    onBlockingChange,
    disabled,
    running,
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
    reply,
  } = props;
  const {
    blocking,
    items,
    pickNotice,
    skillListId,
    textareaRef,
    setCaret,
    fileInputRef,
    sendDisabled,
    outgoing,
  } = state;
  const composerModel = useComposerModel({ ...state, ...props });
  const { saveModelTier } = composerModel;
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

      {reply ? (
        <div className="mb-2">
          <ReplyPreview
            reply={reply.target}
            conversationId={props.conversationId}
            onClear={reply.onClear}
          />
          {running ? (
            // 대기 줄은 글만 받아 집은 것을 붙일 수 없다(스펙 chat-reference DECISION18)
            <p
              data-testid="reply-wait"
              className="mt-1 text-xs text-muted-foreground"
            >
              답이 끝나면 보낼 수 있어요
            </p>
          ) : null}
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

      {/* 입력창은 두 줄이다. 위 줄은 글 칸, 아래 줄은 왼쪽에 「＋」 와 모델 단계, 오른쪽에 중지와 보내기다
          (docs/features/app-shell/spec.md 의 REQ8). 입력창 밖에 따로 떠 있는 줄을 두지 않는다. */}
      <div
        data-testid="composer-shell"
        className={cn(
          "relative flex flex-col gap-1 px-2 pt-1.5 pb-2",
          "rounded-2xl border border-input bg-card focus-within:border-ring",
        )}
      >
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
          aria-controls={skillMenuOpen ? skillListId : undefined}
          aria-activedescendant={
            skillMenuOpen && skillMatches.length > 0
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
            "max-h-[7.5rem] min-h-10 w-full resize-none overflow-y-auto px-2 py-2",
            "bg-transparent text-base leading-6 outline-none disabled:opacity-50",
          )}
        />
        <div
          data-testid="composer-actions"
          className="flex min-w-0 items-center gap-1"
        >
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
              {/* 「＋」 아래에 둘 것이 사진 첨부 하나라 메뉴 없이 바로 고르는 창을 연다(spec 의 DECISION16). */}
              <TooltipButton
                label="사진 첨부"
                variant="outline"
                size="icon"
                onClick={() => fileInputRef.current?.click()}
                disabled={disabled || running || outgoing !== null}
                className="size-9 shrink-0 rounded-full"
              >
                <Plus aria-hidden="true" className="size-5" />
              </TooltipButton>
            </>
          ) : null}
          <div className="flex min-w-0 shrink">
            <ModelTierPicker
              agentCode={agentCode}
              mode={modelSelectionMode}
              tier={modelTier}
              onChange={saveModelTier}
              onFailedChange={setTierFailed}
              disabled={
                disabled || agentCode.length === 0 || modelChoiceUnknown
              }
            />
          </div>
          <div className="ml-auto flex shrink-0 items-center gap-2">
            {running ? (
              <TooltipButton
                label="중지"
                passEscape
                variant="outline"
                disabled={!canStop}
                onClick={onStop}
                size="icon"
                className="size-9 shrink-0 rounded-full"
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
              className="size-9 shrink-0 rounded-full"
            >
              <ArrowUp aria-hidden="true" className="size-5" />
            </TooltipButton>
          </div>
        </div>
      </div>
      {tierFailed ? (
        <p
          role="alert"
          data-testid="model-picker-error"
          className="mt-1 px-2 text-xs text-destructive"
        >
          모델 단계를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </p>
      ) : null}
    </form>
  );
}
