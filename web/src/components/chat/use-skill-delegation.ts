"use client";

import { useEffect, useState } from "react";
import { describeError } from "@/components/error-message";
import {
  fetchSkillDelegationTargets,
  startSkillDelegation,
} from "@/lib/chat-api";
import { readPayload } from "./conversation-session-helpers";
import type { ErrorPayload } from "./conversation-session-types";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";
import type { ConversationQueue } from "./use-conversation-queue";
import { delegatedTargetOf, type SkillDelegationTarget } from "./skill-command";

type Context = Pick<
  ConversationSessionState,
  "draft" | "sending" | "selectionVersion" | "setDraft" | "setError"
> &
  Pick<ConversationHistory, "ensureConversation" | "refreshMessages"> &
  Pick<ConversationQueue, "submit"> & {
    /** 대화의 에이전트가 기본 비서다. 그때만 맡길 스킬을 읽는다 */
    primary: boolean;
  };

/**
 * 비서 대화의 `/` 목록에서 전문 에이전트의 스킬을 맡긴다(docs/features/assistant-hub/spec.md 의 REQ10, DECISION26).
 *
 * <p>글이 목록의 「에이전트 · 스킬」 로 시작하면 보통 보내기와 대기 줄 대신 맡기기 API 로 보낸다. 대화 turn 을 열지 않으므로 답이 도는
 * 동안에도 바로 보낸다. 받아들여지면 입력창을 비우고, 답이 돌고 있지 않으면 이력을 다시 읽어 질문 아래 맡김 줄을 보인다. 답이 돌고
 * 있으면 그 답이 끝날 때 다시 읽는다.
 */
export function useSkillDelegation({
  draft,
  sending,
  selectionVersion,
  setDraft,
  setError,
  ensureConversation,
  refreshMessages,
  primary,
  submit,
}: Context) {
  const [targets, setTargets] = useState<SkillDelegationTarget[]>([]);

  useEffect(() => {
    if (!primary) return;
    let active = true;
    fetchSkillDelegationTargets()
      .then((response) =>
        response.ok
          ? (response.json() as Promise<SkillDelegationTarget[]>)
          : [],
      )
      .then((loaded) => {
        if (active) setTargets(loaded);
      })
      .catch(() => {
        // 읽지 못하면 비서 스킬만 보인다.
      });
    return () => {
      active = false;
    };
  }, [primary]);

  /**
   * 맡기기면 보내고 받아들여졌는지 돌려준다. 맡기기가 아니면 null 이고 부르는 쪽이 보통 보내기로 간다.
   *
   * @param replacementText 질문 카드처럼 입력창과 무관한 글. 없으면 입력창의 글이다
   */
  async function delegate(
    attachmentIds: number[],
    replacementText?: string,
  ): Promise<boolean | null> {
    const visible = primary ? targets : [];
    const text = (replacementText ?? draft).trim();
    const target = delegatedTargetOf(text, visible);
    if (target === null) return null;
    if (attachmentIds.length > 0) {
      setError("사진은 맡길 때 함께 보낼 수 없어요. 사진을 빼고 보내 주세요.");
      return false;
    }
    const version = selectionVersion.current;
    setError(null);
    try {
      const conversationId = await ensureConversation();
      if (conversationId === null) return false;
      const response = await startSkillDelegation({
        conversationId,
        agentCode: target.agentCode,
        text,
      });
      if (!response.ok) {
        const payload = await readPayload<ErrorPayload>(response);
        if (selectionVersion.current === version)
          setError(
            describeError(payload.code, payload.message ?? "맡기지 못했어요."),
          );
        return false;
      }
      if (replacementText === undefined)
        setDraft((current) => (current.trim() === text ? "" : current));
      if (!sending && selectionVersion.current === version)
        await refreshMessages(conversationId, version);
      return true;
    } catch (reason) {
      if (selectionVersion.current === version)
        setError(reason instanceof Error ? reason.message : "맡기지 못했어요.");
      return false;
    }
  }

  /** 입력창과 질문 카드의 보내기다. 맡기기면 맡기고, 아니면 보통 보내기와 대기 경로로 간다. */
  const submitOrDelegate: ConversationQueue["submit"] = async (
    attachmentIds,
    replacementText,
    attachments,
    reply,
  ) =>
    (await delegate(attachmentIds, replacementText)) ??
    submit(attachmentIds, replacementText, attachments, reply);

  return {
    delegationTargets: primary ? targets : [],
    submit: submitOrDelegate,
  };
}
