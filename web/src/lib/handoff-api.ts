import { callConnectorApi } from "@/lib/connector-action";
import type {
  Handoff,
  HandoffCloseResult,
  HandoffOutcome,
} from "@/lib/handoff";

/** 그 대화의 열린 화면 넘기기를 읽는다. */
export const readHandoffs = (conversationId: string) =>
  callConnectorApi<Handoff[]>(
    `/api/chat/conversations/${conversationId}/handoffs`,
  );

/** 넘기기 카드 하나를 닫는다. 답이 단추의 글을 보낼지 정한다(`handoffCloseSends`). */
export const closeHandoff = (
  conversationId: string,
  handoffId: string,
  outcome: HandoffOutcome,
) =>
  callConnectorApi<{ result: HandoffCloseResult }>(
    `/api/chat/conversations/${conversationId}/handoffs/${handoffId}/close`,
    { method: "POST", body: { outcome } },
  );
