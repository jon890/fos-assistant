"use client";

import { describeError } from "@/components/error-message";
import { parseSkillCommand } from "./skill-command";
import { emptyActivity, failActivity } from "./activity/activity-state";
import { sendChatMessage, startChatStream } from "@/lib/chat-api";
import {
  TurnStreamState,
  ErrorPayload,
  ConversationSessionProps,
} from "./conversation-session-types";
import {
  savedIdsOf,
  lastQuestionIsNew,
  readPayload,
} from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";
import type { ConversationQueue } from "./use-conversation-queue";
import type { ConversationEvents } from "./use-conversation-events";
import type { MessageAttachment } from "./message-types";

type Context = Pick<
  ConversationSessionState,
  | "draft"
  | "sending"
  | "selectionVersion"
  | "turns"
  | "sentTurnToken"
  | "setSending"
  | "setError"
  | "setTurnError"
  | "setUnknownSkill"
  | "setDraft"
  | "setActivity"
  | "setLiveExpanded"
  | "liveExpandedRef"
  | "setExpandedOnDone"
  | "currentExecutionId"
  | "setExecutionId"
  | "setStopRequested"
  | "setFlowIsSlow"
  | "setTurns"
  | "conversationIdRef"
  | "refresh"
> &
  Pick<ConversationSessionProps, "conversationId" | "agentCode"> &
  Pick<
    ConversationHistory,
    "refreshMessages" | "settleInterruptedStream" | "assignConversationId"
  > &
  Pick<ConversationQueue, "queueMessage"> &
  Pick<ConversationEvents, "consumeTurnStream" | "finishSentTurn">;

export function useConversationSend({
  draft,
  sending,
  selectionVersion,
  turns,
  sentTurnToken,
  setSending,
  setError,
  setTurnError,
  setUnknownSkill,
  setDraft,
  setActivity,
  setLiveExpanded,
  liveExpandedRef,
  setExpandedOnDone,
  currentExecutionId,
  setExecutionId,
  setStopRequested,
  setFlowIsSlow,
  setTurns,
  conversationIdRef,
  refresh,
  conversationId,
  agentCode,
  refreshMessages,
  settleInterruptedStream,
  queueMessage,
  assignConversationId,
  consumeTurnStream,
  finishSentTurn,
}: Context) {
  /** 전송이 실제로 끝났는지를 돌려준다. `Composer` 는 이 값을 보고 실패했을 때 미리보기를 남긴다 */
  async function send(
    attachmentIds: number[],
    replacementText?: string,
    attachments?: MessageAttachment[],
  ): Promise<boolean> {
    const text = (replacementText ?? draft).trim();
    if (
      text.length === 0 ||
      sending ||
      (conversationId === null && agentCode.length === 0)
    )
      return false;
    const version = selectionVersion.current;
    const pendingId = `pending-${Date.now()}`;
    const assistantPendingId = `assistant-${Date.now()}`;
    const savedBefore = savedIdsOf(turns);
    /** 스트림이 끊겨 보는 창으로 넘어갔다. 보기가 입력창을 풀므로 끝낼 때 풀지 않는다. */
    let handedOff = false;
    sentTurnToken.current = pendingId;
    setSending(true);
    setError(null);
    setTurnError(null);
    setUnknownSkill(null);
    // 글을 인자로 받았으면 입력창의 글과 무관하게 보낸다. 추천 질문이 그렇다.
    if (replacementText === undefined) setDraft("");
    setActivity(emptyActivity(Date.now()));
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
    const finishFailedActivity = () => {
      if (selectionVersion.current !== version) return;
      setActivity((previous) => previous && failActivity(previous, Date.now()));
      setLiveExpanded(false);
      liveExpandedRef.current = false;
      setFlowIsSlow(false);
    };
    setTurns((previous) => [
      ...previous,
      {
        id: pendingId,
        role: "USER",
        content: text,
        senderName: null,
        attachments,
      },
    ]);
    const restoreFailedMessage = () => {
      if (selectionVersion.current !== version) return;
      if (replacementText === undefined) setDraft(text);
      setTurns((previous) =>
        previous.filter(
          (turn) => turn.id !== pendingId && turn.id !== assistantPendingId,
        ),
      );
    };
    const refreshAfterStartedFailure = async (): Promise<boolean> => {
      if (conversationIdRef.current === null) return false;
      try {
        await refreshMessages(conversationIdRef.current, version);
        return true;
      } catch {
        // `started` 뒤에는 서버에 질문이 남는다. 다만 새 이력을 못 받았을 때 임시 질문을 저장된 판처럼
        // 보이면 다음 다시 시도가 무엇을 대상으로 하는지 알 수 없으므로 화면에서 치운다.
        setTurns((previous) =>
          previous.filter(
            (turn) => turn.id !== pendingId && turn.id !== assistantPendingId,
          ),
        );
        return false;
      }
    };
    const stream: TurnStreamState = {
      started: false,
      done: false,
      reportedError: false,
    };
    const startedFailureMessage = (message: string, refreshed: boolean) =>
      refreshed
        ? message
        : `${message} 대화 이력을 다시 읽지 못했어요. 아래에서 다시 시도하거나 대화를 새로고침해 주세요.`;

    /** 끝 사건 없이 끊긴 스트림을 마무리한다. 돌고 있거나 답이 저장됐으면 오류로 끝내지 않는다. */
    const finishInterrupted = async (): Promise<boolean> => {
      // 질문이 이미 저장돼 글을 되돌리지 않았으면 전송이 끝난 것으로 알린다. 입력창이 첨부 미리보기를 남기지 않게 한다.
      let questionKept = false;
      const kind = await settleInterruptedStream(
        version,
        savedBefore,
        assistantPendingId,
        {
          onMissing: async (history) => {
            finishFailedActivity();
            const message = describeError(
              "STREAM_INTERRUPTED",
              "응답 연결이 끊겼어요.",
            );
            const questionSaved =
              stream.started ||
              (history !== null && lastQuestionIsNew(history, savedBefore));
            if (questionSaved && conversationIdRef.current !== null) {
              questionKept = true;
              const refreshed =
                history !== null || (await refreshAfterStartedFailure());
              if (selectionVersion.current === version)
                setTurnError(startedFailureMessage(message, refreshed));
            } else {
              restoreFailedMessage();
              if (selectionVersion.current === version) setError(message);
            }
          },
        },
      );
      if (kind === "observing") handedOff = true;
      return (
        kind === "observing" ||
        kind === "answered" ||
        stream.started ||
        questionKept
      );
    };

    /**
     * `started` 전에 거절된 보내기를 알린다. 없는 스킬 커맨드는 입력창 위 오류 대신 입력창 아래에 이름으로 알린다.
     * 스트림 사건, 스트림의 HTTP 오류, 스트림 없이 보낸 응답 셋이 모두 이 길로 온다.
     */
    const reportRejected = (code: string, message: string) => {
      const command =
        code === "SKILL_COMMAND_UNKNOWN" ? parseSkillCommand(text) : null;
      if (command === null) {
        setError(describeError(code, message));
      } else if (selectionVersion.current === version) {
        setUnknownSkill(command.name);
      }
    };

    /** `started` 전에 거절된 글을 대기 메시지로 넣었다. 전송이 끝난 것으로 알린다. */
    let queuedInstead = false;
    /**
     * `started` 전에 거절된 보내기를 마무리한다.
     *
     * <p>`CONVERSATION_BUSY` 는 자동 turn 이 막 열린 순간에 보낸 경우다. 화면은 아직 그 turn 을 모른다. 글을
     * 입력창에 되돌리지 않고 대기 메시지로 넣는다. 사진을 실었으면 대기 메시지가 글만 받으므로 되돌린다.
     */
    const rejectBeforeStart = async (
      code: string,
      message: string,
    ): Promise<boolean> => {
      if (
        code === "CONVERSATION_BUSY" &&
        attachmentIds.length === 0 &&
        conversationIdRef.current !== null
      ) {
        setTurns((previous) =>
          previous.filter(
            (turn) => turn.id !== pendingId && turn.id !== assistantPendingId,
          ),
        );
        if (await queueMessage(text, false)) {
          queuedInstead = true;
          return true;
        }
        // 대기 메시지로도 넣지 못했다. 까닭은 대기 경로가 이미 알렸으므로 글만 되돌린다.
        restoreFailedMessage();
        return false;
      }
      restoreFailedMessage();
      reportRejected(code, message);
      return false;
    };
    const requestBody = {
      conversationId,
      text,
      agentCode,
      attachmentIds,
    };
    const sendWithoutStream = async () => {
      const response = await sendChatMessage(requestBody);
      const payload = await readPayload<
        ErrorPayload & { conversationId: string; assistantText: string }
      >(response);
      if (!response.ok) return rejectBeforeStart(payload.code, payload.message);
      if (selectionVersion.current !== version) return true;
      if (conversationIdRef.current === null) {
        window.history.replaceState(
          null,
          "",
          `/chat/${payload.conversationId}`,
        );
      }
      assignConversationId(payload.conversationId);
      setTurns((previous) => [
        ...previous,
        {
          id: `assistant-${Date.now()}`,
          role: "ASSISTANT",
          content: payload.assistantText,
          senderName: null,
        },
      ]);
      await Promise.all([
        refresh(),
        refreshMessages(payload.conversationId, version),
      ]);
      return true;
    };
    try {
      let response: Response;
      try {
        response = await startChatStream(requestBody);
      } catch {
        return await sendWithoutStream();
      }
      if (!response.ok) {
        if ([404, 405, 415, 501].includes(response.status)) {
          return await sendWithoutStream();
        }
        const payload = await readPayload<ErrorPayload>(response);
        return await rejectBeforeStart(payload.code, payload.message);
      }

      try {
        await consumeTurnStream(response, version, stream, {
          onStarted: (event) => {
            if (!event.conversationId) return;
            if (conversationIdRef.current === null)
              window.history.replaceState(
                null,
                "",
                `/chat/${event.conversationId}`,
              );
            assignConversationId(event.conversationId);
            void refresh();
          },
          onDelta: (textDelta) => {
            setTurns((previous) => {
              const current = previous.find(
                (turn) => turn.id === assistantPendingId,
              );
              if (!current)
                return [
                  ...previous,
                  {
                    id: assistantPendingId,
                    role: "ASSISTANT",
                    content: textDelta,
                    senderName: null,
                  },
                ];
              return previous.map((turn) =>
                turn.id === assistantPendingId
                  ? { ...turn, content: turn.content + textDelta }
                  : turn,
              );
            });
          },
          onReset: () => {
            // 막혀서 넘어간 시도의 조각이다. 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== assistantPendingId),
            );
          },
          onDone: async (event) => {
            assignConversationId(event.conversationId!);
            await Promise.all([
              refresh(),
              refreshMessages(event.conversationId!, version),
            ]);
          },
          onError: async (event) => {
            const code = event.code ?? "INTERNAL_ERROR";
            const fallback = event.message ?? "요청을 처리하지 못했어요.";
            if (stream.started && conversationIdRef.current !== null) {
              const refreshed = await refreshAfterStartedFailure();
              setTurnError(
                startedFailureMessage(describeError(code, fallback), refreshed),
              );
            } else {
              await rejectBeforeStart(code, fallback);
            }
          },
        });
      } catch {
        if (!stream.done && !stream.reportedError)
          return await finishInterrupted();
        return queuedInstead || stream.started || stream.done;
      }
      if (!stream.done && !stream.reportedError)
        return await finishInterrupted();
      return queuedInstead || stream.started || stream.done;
    } catch (reason) {
      finishFailedActivity();
      restoreFailedMessage();
      setError(
        reason instanceof Error ? reason.message : "요청을 보내지 못했어요.",
      );
      return false;
    } finally {
      if (selectionVersion.current === version && !handedOff) setSending(false);
      void finishSentTurn(pendingId);
    }
  }
  return { send };
}
export type ConversationSend = {
  send(
    attachmentIds: number[],
    replacementText?: string,
    attachments?: MessageAttachment[],
  ): Promise<boolean>;
};
