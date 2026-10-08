"use client";

import Link from "next/link";
import { cn } from "cn";
import { Composer } from "./composer";
import { StartScreenHeader, StarterPrompts } from "./start-screen";
import { PendingQueueView } from "./pending-queue";
import { ApprovalList } from "./approval-list";
import { MessageList } from "./message-list";
import { ActivityPanel } from "./activity/activity-panel";
import { ArtifactPanel } from "./artifact/artifact-panel";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { agentLabel } from "@/lib/format";
import type { Props } from "./conversation-session-view-types";
import { useCheckFindings } from "./use-check-findings";

export function ConversationSessionView({
  notFound,
  checkStarting,
  checkError,
  messagesLoading,
  turns,
  sending,
  activity,
  flowIsSlow,
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
  setDraft,
  conversationIdRef,
  refresh,
  executionId,
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
  agentsLoading,
  onAgentCodeChange,
  assignConversationId,
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
  skillNames,
  starters,
}: Props) {
  // 저장된 답만 본다. 흘러오는 중인 답의 발견은 아직 없고, 저장된 답으로 바뀌면 번호가 바뀌어 다시 읽는다.
  const lastAnswerExecutionId =
    turns.findLast(
      (turn) =>
        turn.role === "ASSISTANT" &&
        typeof turn.id === "number" &&
        typeof turn.executionId === "number",
    )?.executionId ?? null;
  const checkFindings = useCheckFindings(
    conversationId,
    currentConversation?.purpose === "CHECK",
    String(turns.length),
    lastAnswerExecutionId,
  );

  if (notFound) {
    return (
      <section
        data-testid="conversation-not-found"
        className="mx-auto max-w-3xl py-12 text-center"
      >
        <h1 className="text-lg font-semibold">대화를 찾을 수 없어요</h1>
        <Link
          href="/"
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
        {startScreen ? null : (
          <div className="flex min-w-0 items-center gap-3 border-b border-border pb-3">
            <div
              className={cn(
                "flex min-w-0 flex-1 items-center gap-3 overflow-hidden",
                "text-xs text-muted-foreground",
              )}
            >
              <div
                className={`min-w-0 items-center gap-2 ${agentLocked ? "hidden md:flex" : "flex"}`}
              >
                <span className="shrink-0">에이전트</span>
                <span
                  className="truncate"
                  title={agentMissing ? agentLabel(null) : currentAgent?.name}
                >
                  {agentMissing
                    ? agentLabel(null)
                    : (currentAgent?.name ?? "등록된 에이전트가 없어요")}
                </span>
              </div>
            </div>
            {currentConversation?.purpose === "CHECK" ? (
              <Button
                variant="outline"
                size="sm"
                className="shrink-0"
                disabled={sending}
                loading={checkStarting}
                loadingText="시작하는 중"
                onClick={() => void startCheck()}
              >
                지금 살펴보기
              </Button>
            ) : null}
          </div>
        )}
        {startScreen || checkError === null ? null : (
          <Notice variant="error" role="alert" className="mt-3">
            {checkError}
          </Notice>
        )}

        {startScreen ? null : (
          <MessageList
            turns={turns}
            loading={messagesLoading}
            sending={sending}
            activity={activity}
            conversationId={conversationId}
            flowIsSlow={flowIsSlow}
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
            // 에이전트 목록을 읽기 전이거나 목록에 없는 에이전트의 대화는 흐름인지 모르므로 칩을 붙이지 않는다.
            skillCommandChips={currentAgent?.acceptsAttachments === true}
            selectedVersions={selectedVersions}
            onVersionChange={(slotId, index) =>
              setSelectedVersions((previous) => ({
                ...previous,
                [slotId]: index,
              }))
            }
            onRegenerate={() => {
              void regenerate();
            }}
            onRetry={() => {
              void regenerate();
            }}
            onRetryDelivery={(deliveryId) => {
              void retryDeliveryTurn(deliveryId);
            }}
            // 다른 turn 이 도는 동안에는 보내도 서버가 막으므로 단추도 막는다.
            deliveryRetrying={deliveryRetrying || sending}
            memoryCaptures={memoryCaptures.captures}
            onMemoryCapturesChanged={(removedId) => {
              // 기억 기록을 되돌리거나 고치거나 받아들이면 참고한 기억도 바뀐다
              memoryCaptures.changed(removedId);
              memoryUses.reload();
            }}
            memoryUses={memoryUses.uses}
            checkFindings={checkFindings.findings}
            dismissWindowDays={checkFindings.dismissWindowDays}
            onCheckFindingsChanged={checkFindings.changed}
            // 질문 카드는 답이 오는 중에도 보인다. 그때 고른 답은 입력창의 보내기와 같이 대기 메시지로 들어간다.
            onAnswer={(text) => {
              void submit([], text);
            }}
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
              selectedCode={agentCode}
              onSelect={onAgentCodeChange}
              locked={agentLocked}
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
          <ApprovalList
            conversationId={conversationId}
            refreshKey={approvalRefresh}
          />
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
            onSend={(attachmentIds) => submit(attachmentIds)}
            disabled={conversationId === null && agents.length === 0}
            conversationId={conversationId}
            agentCode={agentMissing ? "" : agentCode}
            acceptsAttachments={currentAgent?.acceptsAttachments ?? false}
            onConversationCreated={(id) => {
              if (conversationIdRef.current === null) {
                window.history.replaceState(null, "", `/chat/${id}`);
              }
              assignConversationId(id);
              void refresh();
            }}
            running={sending}
            // 새 대화의 첫 turn 은 `started` 가 대화 식별자를 실어 온 뒤부터 대기 메시지를 받는다.
            canQueue={conversationId !== null}
            canStop={executionId !== null && !stopRequested}
            onStop={() => {
              void stop();
            }}
            mention={
              startScreen && !agentLocked && agents.length > 0
                ? { agents, onPick: onAgentCodeChange }
                : undefined
            }
            skillNames={skillNames}
            onBlockingChange={setComposerBlocking}
            modelChoice={
              currentConversation
                ? {
                    provider: currentConversation.provider,
                    model: currentConversation.model,
                    reasoningEffort: currentConversation.reasoningEffort,
                  }
                : null
            }
            modelChoiceUnknown={
              conversationId !== null && currentConversation === undefined
            }
            modelSelectionMode={currentConversation?.modelSelectionMode ?? null}
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
    </section>
  );
}
