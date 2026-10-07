"use client";

import {
  type ModelChoice,
  type ModelChoiceSaveResult,
  type ModelTierSaveResult,
} from "./model-picker";
import type { Conversation } from "@/components/shell/conversations-provider";
import { saveConversationTier, type ModelTierCode } from "@/lib/model-tiers";
import { createConversation, saveConversationModel } from "@/lib/chat-api";
import { Props } from "./composer-types";
import type { ComposerState } from "./use-composer-state";

type Context = Pick<
  ComposerState,
  | "creatingConversationRef"
  | "setCreatingConversation"
  | "setPickNotice"
  | "mountedRef"
  | "setSavingModel"
> &
  Pick<
    Props,
    | "conversationId"
    | "agentCode"
    | "onConversationCreated"
    | "onModelChoiceSaved"
  >;

export function useComposerModel({
  creatingConversationRef,
  setCreatingConversation,
  setPickNotice,
  mountedRef,
  setSavingModel,
  conversationId,
  agentCode,
  onConversationCreated,
  onModelChoiceSaved,
}: Context) {
  async function ensureConversationId(): Promise<string | null> {
    if (conversationId !== null) return conversationId;
    if (creatingConversationRef.current) return creatingConversationRef.current;

    const promise = (async () => {
      setCreatingConversation(true);
      try {
        const response = await createConversation(agentCode);
        const payload = (await response.json()) as {
          conversationId?: string;
          code?: string;
          message?: string;
        };
        if (!response.ok || !payload.conversationId) {
          setPickNotice(
            "대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
          );
          return null;
        }
        // 요청 도중 대화를 바꿨으면 부모는 이미 다른 대화를 보고 있다. 그 선택을 덮지 않는다.
        if (!mountedRef.current) return null;
        onConversationCreated(payload.conversationId);
        return payload.conversationId;
      } catch {
        setPickNotice("대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        return null;
      } finally {
        creatingConversationRef.current = null;
        if (mountedRef.current) setCreatingConversation(false);
      }
    })();
    creatingConversationRef.current = promise;
    return promise;
  }

  /**
   * 고른 모델을 대화에 저장한다. 대화가 아직 없으면 사진을 먼저 올릴 때처럼 빈 대화를 만든다.
   *
   * <p>대화를 만든 것은 `ensureConversationId` 가 이미 알렸으므로 여기서 다시 알리지 않는다.
   */
  async function saveModelChoice(
    choice: ModelChoice,
  ): Promise<ModelChoiceSaveResult> {
    setSavingModel(true);
    try {
      const targetConversationId = await ensureConversationId();
      // 빈 대화를 만들지 못했으면 `ensureConversationId` 가 이미 알렸다.
      if (targetConversationId === null || !mountedRef.current)
        return "reported";
      const response = await saveConversationModel(
        targetConversationId,
        choice,
      );
      if (!response.ok) return "failed";
      // 기다리는 동안 대화를 바꿨어도 알린다. 받은 줄은 그 대화의 것이라 목록의 그 줄만 바뀐다.
      onModelChoiceSaved((await response.json()) as Conversation);
      return "saved";
    } catch {
      return "failed";
    } finally {
      if (mountedRef.current) setSavingModel(false);
    }
  }

  /** 고른 단계를 대화에 저장한다. 기본값으로 돌아가기도 같은 경로에서 명시적으로 적는다. */
  async function saveModelTier(
    mode: "DEFAULT" | "TIER",
    tier: ModelTierCode | null,
  ): Promise<ModelTierSaveResult> {
    setSavingModel(true);
    try {
      const targetConversationId = await ensureConversationId();
      if (targetConversationId === null || !mountedRef.current)
        return "reported";
      const result = await saveConversationTier<Conversation>(
        targetConversationId,
        mode,
        tier,
      );
      if (!result.ok) return "failed";
      onModelChoiceSaved(result.data);
      return "saved";
    } catch {
      return "failed";
    } finally {
      if (mountedRef.current) setSavingModel(false);
    }
  }
  return { ensureConversationId, saveModelChoice, saveModelTier };
}
export type ComposerModel = ReturnType<typeof useComposerModel>;
