"use client";

import { type ModelTierSaveResult } from "./model-picker";
import type { Conversation } from "@/components/shell/conversations-provider";
import { saveConversationTier, type ModelTierCode } from "@/lib/model-tiers";
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
  Pick<Props, "conversationId" | "ensureConversation" | "onModelChoiceSaved">;

export function useComposerModel({
  creatingConversationRef,
  setCreatingConversation,
  setPickNotice,
  mountedRef,
  setSavingModel,
  conversationId,
  ensureConversation,
  onModelChoiceSaved,
}: Context) {
  async function ensureConversationId(): Promise<string | null> {
    if (conversationId !== null) return conversationId;
    if (creatingConversationRef.current) return creatingConversationRef.current;

    const promise = (async () => {
      setCreatingConversation(true);
      try {
        const id = await ensureConversation();
        // 요청 도중 대화를 바꿨으면 부모는 이미 다른 대화를 보고 있다. 그 선택을 덮지 않는다.
        if (!mountedRef.current) return null;
        return id;
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
   * 고른 칸을 대화에 저장한다. 대화가 아직 없으면 사진을 먼저 올릴 때처럼 빈 대화를 만든다.
   *
   * <p>대화를 만든 것은 `ensureConversationId` 가 이미 알렸으므로 여기서 다시 알리지 않는다. 서버는 같은 저장에서
   * 사용자 기본 칸도 바꾼다.
   */
  async function saveModelTier(
    mode: "TIER",
    tier: ModelTierCode,
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
  return { ensureConversationId, saveModelTier };
}
export type ComposerModel = ReturnType<typeof useComposerModel>;
