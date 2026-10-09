import type { AgentView } from "@/lib/agent";
import { type ModelChoice } from "./model-picker";
import type { Conversation } from "@/components/shell/conversations-provider";
import { type ModelTierCode } from "@/lib/model-tiers";
import type { MessageAttachment } from "./message-types";
import type { OutgoingMessage } from "./composer-attachment-utils";

export type Props = {
  value: string;
  disabled: boolean;
  onChange(value: string): void;
  /** 전송이 실제로 끝났는지를 돌려준다. 실패하면 미리보기를 지우지 않는다 */
  onSend(
    attachmentIds: number[],
    text?: string,
    attachments?: MessageAttachment[],
  ): Promise<boolean>;
  onOutgoingChange(message: OutgoingMessage | null): void;
  /** 대화가 아직 없으면 null. 사진을 고르면 이 값이 없는 채로 첫 사진을 올릴 수 없다 */
  conversationId: string | null;
  agentCode: string;
  /** 이 에이전트의 대화에 사진을 붙일 수 있다. 거짓이면 사진 단추를 그리지 않는다 */
  acceptsAttachments: boolean;
  onConversationCreated(id: string): void;
  /** 답을 만드는 중이다. 참이면 보내기 옆에 중지를 함께 보인다. */
  running: boolean;
  /** 답을 만드는 중에도 보내기를 받을 수 있다. 거짓이면 그동안 보내기와 Enter 를 막는다. */
  canQueue: boolean;
  /** `started` 사건 뒤, 아직 중지를 누르지 않았을 때 참이다. */
  canStop: boolean;
  onStop(): void;
  /**
   * 입력칸에 `@` 를 치면 에이전트를 고르는 목록을 띄운다. 새 대화에서만 준다.
   * 대화의 에이전트는 첫 메시지가 정하고 그 뒤로 바뀌지 않는다.
   */
  mention?: { agents: AgentView[]; onPick(code: string): void };
  /**
   * 입력칸 맨 앞에 `/` 를 치면 이 스킬 이름 목록을 띄운다. 비었으면 스킬이 없다고 알린다.
   * 흐름이 붙은 에이전트이거나 목록을 아직 읽지 못했으면 주지 않고, 그때는 목록을 띄우지 않는다.
   */
  skillNames?: string[];
  /**
   * 보내기를 막는 일이 도는지 알린다. 빈 대화를 만드는 요청이나 끝나지 않은 첨부가 그렇다.
   * 입력창을 거치지 않고 보내는 추천 질문도 이 동안은 막아야 대화가 둘 생기지 않는다.
   */
  onBlockingChange?(blocking: boolean): void;
  /** 이 대화에 적힌 모델 선택이다. 대화가 아직 없으면 null */
  modelChoice: ModelChoice | null;
  /**
   * 대화는 있는데 그 대화에 적힌 모델 선택을 아직 모른다. 대화 목록이 오기 전이 그렇다.
   * 이때 고르게 하면 모르는 값을 「기본」 으로 보고 저장해 적힌 모델을 지운다. 그래서 단추를 막는다.
   */
  modelChoiceUnknown: boolean;
  modelSelectionMode: "DEFAULT" | "TIER" | "CUSTOM" | null;
  modelTier: ModelTierCode | null;
  /** 모델 선택을 저장한 뒤 서버가 돌려준 대화 한 줄을 알린다 */
  onModelChoiceSaved(conversation: Conversation): void;
};
