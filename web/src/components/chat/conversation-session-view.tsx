"use client";

import Link from "next/link";
import { useReplyTarget } from "./use-reply-target";
import { Composer } from "./composer";
import { ConversationHeader } from "./conversation-header";
import {
  StartAgentTag,
  StartScreenHeader,
  StarterPrompts,
} from "./start-screen";
import { PendingQueueView } from "./pending-queue";
import { ConversationGrantList } from "./conversation-grant-list";
import { MessageList } from "./message-list";
import { ActivityPanel } from "./activity/activity-panel";
import { ArtifactPanel } from "./artifact/artifact-panel";
import { Notice } from "@/components/ui/notice";
import { NEW_CONVERSATION_PATH } from "@/lib/room";
import { ReadOnlyNotice } from "./read-only-notice";
import { primaryCode } from "@/lib/agent";
import { useConversationTodo } from "./use-conversation-todo";
import type { Props } from "./conversation-session-view-types";

export function ConversationSessionView({
  notFound,
  checkStarting,
  checkError,
  messagesLoading,
  turns,
  sending,
  activity,
  liveExpanded,
  liveExpandedRef,
  setLiveExpanded,
  expandedOnDone,
  turnError,
  setPanelTarget,
  selectedVersions,
  setSelectedVersions,
  deliveryRetrying,
  memoryCaptures,
  memoryUses,
  displayName,
  error,
  observing,
  approvalRefresh,
  pending,
  pendingBusy,
  draft,
  outgoing,
  setOutgoing,
  setDraft,
  executionId,
  liveDelegations,
  liveImages,
  stopRequested,
  setComposerBlocking,
  replace,
  unknownSkill,
  composerBlocking,
  panelTarget,
  conversationId,
  currentConversation,
  agents,
  agentCode,
  answerAgent,
  agentsLoading,
  agentsError,
  onRetryAgents,
  ensureConversation,
  submit,
  cancelPendingMessage,
  releasePendingMessages,
  regenerate,
  retryDeliveryTurn,
  startCheck,
  stop,
  send,
  agentLocked,
  agentMissing,
  currentAgent,
  startScreen,
  skillCommandsEnabled,
  skillNames,
  starters,
}: Props) {
  const { replyTo, replyRef, setReplyTo } = useReplyTarget();
  // 대화의 에이전트가 기본 비서가 아닌 지난 대화는 읽기만 한다(스펙 assistant-hub REQ5). 서버도 쓰는 요청을 거절한다.
  const readOnly = currentConversation?.writable === false;
  const todo = useConversationTodo({
    conversationId,
    refreshKey: approvalRefresh,
    panelTarget,
    setPanelTarget,
    submit: (text) => void submit([], text),
    turns,
    selectedVersions,
    sending,
    activity,
    readOnly,
  });

  if (notFound) {
    return (
      <section
        data-testid="conversation-not-found"
        className="mx-auto max-w-3xl py-12 text-center"
      >
        <h1 className="text-lg font-semibold">대화를 찾을 수 없어요</h1>
        <Link
          href={NEW_CONVERSATION_PATH}
          className="mt-4 inline-block rounded-md bg-muted px-3 py-2 text-sm"
        >
          새 대화
        </Link>
      </section>
    );
  }

  // 입력창은 첫 메시지 전후로 같은 자리에 하나만 둔다. 앞뒤 형제만 조건부로 그리고, 가운데에서 아래로
  // 옮기는 것은 감싸는 요소의 클래스로만 한다. 부모가 바뀌면 입력창이 새로 만들어져 올린 사진이 지워진다.
  return (
    <section className="relative flex h-full min-h-0 min-w-0">
      {startScreen ? null : <h1 className="sr-only">대화</h1>}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {startScreen ? (
          <StartAgentTag
            agents={agents}
            loading={agentsLoading}
            agentsError={agentsError}
            selectedCode={agentCode}
          />
        ) : (
          <ConversationHeader
            answerAgent={answerAgent}
            agentLocked={agentLocked}
            agentMissing={agentMissing}
            agentName={currentAgent?.name}
            check={
              currentConversation?.purpose === "CHECK"
                ? { sending, starting: checkStarting, start: startCheck }
                : null
            }
          />
        )}
        {startScreen || checkError === null ? null : (
          <Notice variant="error" role="alert" className="mt-3">
            {checkError}
          </Notice>
        )}

        {startScreen ? null : (
          <MessageList
            turns={turns}
            liveDelegations={liveDelegations}
            liveImages={liveImages}
            liveExecutionId={executionId}
            outgoing={outgoing}
            loading={messagesLoading}
            sending={sending}
            activity={activity}
            conversationId={conversationId}
            liveExpanded={liveExpanded}
            onLiveExpandedChange={(value) => {
              liveExpandedRef.current = value;
              setLiveExpanded(value);
            }}
            expandedOnDone={expandedOnDone}
            turnError={turnError}
            onOpenSaved={(executionId) =>
              setPanelTarget({
                kind: "activity",
                target: { mode: "saved", executionId },
              })
            }
            onOpenLive={() => {
              if (activity)
                setPanelTarget({
                  kind: "activity",
                  target: { mode: "live", state: activity },
                });
            }}
            onOpenArtifact={(messageId, path, name) =>
              setPanelTarget({ kind: "artifact", messageId, path, name })
            }
            // 목록과 칩은 같은 지원 여부를 쓴다. 에이전트 행을 모르면 둘 다 보이지 않는다.
            skillCommandChips={skillCommandsEnabled}
            selectedVersions={selectedVersions}
            onVersionChange={(slotId, index) =>
              setSelectedVersions((previous) => ({
                ...previous,
                [slotId]: index,
              }))
            }
            onRegenerate={
              readOnly
                ? undefined
                : () => {
                    void regenerate();
                  }
            }
            onRetry={
              readOnly
                ? undefined
                : () => {
                    void regenerate();
                  }
            }
            onRetryDelivery={
              readOnly
                ? undefined
                : (deliveryId) => {
                    void retryDeliveryTurn(deliveryId);
                  }
            }
            // 다른 turn 이 도는 동안에는 보내도 서버가 막으므로 단추도 막는다.
            deliveryRetrying={deliveryRetrying || sending}
            memoryCaptures={memoryCaptures.captures}
            onMemoryCapturesChanged={(removedId) => {
              // 기억 기록을 되돌리거나 고치거나 받아들이면 참고한 기억도 바뀐다
              memoryCaptures.changed(removedId);
              memoryUses.reload();
            }}
            memoryUses={memoryUses.uses}
            // 질문 카드는 답이 오는 중에도 보인다. 그때 고른 답은 입력창의 보내기와 같이 대기 메시지로 들어간다.
            onAnswer={
              readOnly
                ? undefined
                : (text) => {
                    void submit([], text);
                  }
            }
            onReply={readOnly ? undefined : setReplyTo}
          />
        )}

        <div
          className={
            startScreen
              ? "flex min-h-0 flex-1 flex-col overflow-y-auto"
              : "shrink-0"
          }
        >
          {startScreen ? (
            <StartScreenHeader
              displayName={displayName}
              agents={agents}
              loading={agentsLoading}
              agentsError={agentsError}
              onRetryAgents={onRetryAgents}
            />
          ) : null}
          {error || observing ? (
            <div className="px-1">
              <div className="mx-auto w-full max-w-3xl">
                {error ? (
                  <Notice variant="error" className="mb-2">
                    {error}
                  </Notice>
                ) : null}
                {observing ? (
                  <p
                    data-testid="observing-notice"
                    className="mb-2 text-xs text-muted-foreground"
                  >
                    {observing.sentHere
                      ? "응답 연결이 끊겨 답을 기다리고 있어요. 답이 완성되면 여기에 나타나요."
                      : "다른 창에서 답을 만들고 있어요. 완성되면 이 창에도 나타나요."}
                  </p>
                ) : null}
              </div>
            </div>
          ) : null}
          {todo.summary}
          <ConversationGrantList
            conversationId={conversationId}
            refreshKey={approvalRefresh}
          />
          {readOnly ? (
            <ReadOnlyNotice
              canStop={executionId !== null && !stopRequested}
              onStop={() => {
                void stop();
              }}
            />
          ) : (
            <>
              <PendingQueueView
                queue={pending.queue}
                onCancel={(pendingId) => {
                  void cancelPendingMessage(pendingId);
                }}
                onRelease={() => {
                  void releasePendingMessages();
                }}
                busy={pendingBusy}
              />
              <Composer
                value={draft}
                onChange={setDraft}
                onSend={async (attachmentIds, text, attachments, reply) => {
                  const sent = await submit(
                    attachmentIds,
                    text,
                    attachments,
                    reply,
                  );
                  // 받아들여지지 않았으면 그사이 새로 집은 것이 없을 때만 칩을 되돌린다(스펙 chat-reference REQ9).
                  if (!sent && reply && replyRef.current === null)
                    setReplyTo(reply);
                  return sent;
                }}
                reply={
                  replyTo
                    ? { target: replyTo, onClear: () => setReplyTo(null) }
                    : undefined
                }
                onOutgoingChange={setOutgoing}
                // 새 대화는 기본 비서와만 시작한다. 비서가 없으면 보내지 못한다(스펙 assistant-hub REQ4).
                disabled={conversationId === null && primaryCode(agents) === ""}
                conversationId={conversationId}
                agentCode={agentMissing ? "" : agentCode}
                acceptsAttachments={currentAgent?.acceptsAttachments ?? false}
                ensureConversation={ensureConversation}
                running={sending}
                // 첫 송신 전에 빈 대화 식별자를 확보한다. 답이 도는 동안에는 대기 메시지를 받는다.
                canQueue={conversationId !== null && replyTo === null}
                canStop={executionId !== null && !stopRequested}
                onStop={() => {
                  void stop();
                }}
                skillNames={skillNames}
                onBlockingChange={setComposerBlocking}
                modelChoiceUnknown={
                  conversationId !== null && currentConversation === undefined
                }
                modelSelectionMode={
                  currentConversation?.modelSelectionMode ?? null
                }
                modelTier={currentConversation?.modelTier ?? null}
                // 저장 응답으로 그 줄만 바꾼다. 목록을 다시 읽으면 먼저 나간 읽기가 늦게 와 저장한 줄을 저장 전의 줄로 되돌릴 수 있다.
                onModelChoiceSaved={replace}
              />
              {unknownSkill ? (
                <Notice
                  variant="error"
                  data-testid="skill-command-notice"
                  role="alert"
                  className="mx-auto mt-2 w-full max-w-3xl"
                >
                  /{unknownSkill} 스킬이 이 에이전트에 없어요
                </Notice>
              ) : null}
            </>
          )}
          {startScreen ? (
            <StarterPrompts
              prompts={starters.prompts}
              pending={starters.pending}
              disabled={sending || composerBlocking}
              onPrompt={(text) => {
                void send([], text);
              }}
            />
          ) : null}
        </div>
      </div>
      {panelTarget?.kind === "activity" ? (
        <ActivityPanel
          target={
            panelTarget.target.mode === "live" && activity
              ? { mode: "live", state: activity }
              : panelTarget.target
          }
          onClose={() => setPanelTarget(null)}
        />
      ) : null}
      {panelTarget?.kind === "artifact" && conversationId !== null ? (
        <ArtifactPanel
          key={panelTarget.path}
          conversationId={conversationId}
          path={panelTarget.path}
          name={panelTarget.name}
          onClose={() => setPanelTarget(null)}
        />
      ) : null}
      {todo.panel}
    </section>
  );
}
