import { callConnectorApi } from "@/lib/connector-action";
import type { Handoff } from "@/lib/handoff";

/** 그 대화의 열린 화면 넘기기를 읽는다. */
export const readHandoffs = (conversationId: string) =>
  callConnectorApi<Handoff[]>(
    `/api/chat/conversations/${conversationId}/handoffs`,
  );
