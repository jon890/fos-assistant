"use client";

/**
 * 대화 화면은 두 겹이다.
 *
 * <p>`ChatPanel` 은 에이전트 목록, 새 대화에서 고른 에이전트, 지금 대화의 식별자, 대화 상태를 몇 번째로
 * 만들었는지를 갖는다. 경로가 바뀔 때 Next.js 가 새로 만든다.
 *
 * <p>`ConversationSession` 은 메시지, 보내는 중인 turn, 작업 과정, 옆 패널, 오류 문구, 쓰던 글, 입력창과
 * 올린 사진, 대기 줄, 대화 단위 SSE 연결을 갖는다. `ChatPanel` 이 `key` 를 올릴 때 새로 만들어진다.
 *
 * <p>`key` 는 아래 셋에서만 올린다. 대화 식별자가 새로 생긴 것은 여기 들지 않는다. 그때 새로 만들면 흘러오던
 * 답과 올리던 사진을 잃는다.
 *
 * <ul>
 *   <li>주소가 `/` 로 바뀌었고 대화 식별자를 들고 있다
 *   <li>「새 대화」 를 눌렀다
 *   <li>받은 대화 식별자가 다른 대화로 바뀌었다
 * </ul>
 */

import { useEffect, useRef, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { ConversationSession } from "./chat/conversation-session";
import {
  useConversation,
  useConversations,
} from "./shell/conversations-provider";
import { fetchChatAgents } from "@/lib/chat-api";
import { agentCodeParam, type AgentView } from "@/lib/agent";

export function ChatPanel({
  initialConversationId,
}: {
  initialConversationId: string | null;
}) {
  const pathname = usePathname();
  // 「<에이전트>와 대화하기」 가 `/?agent=<번호>` 로 연다. 목록에 있는 에이전트일 때만 그 에이전트를 고른다.
  // 첫 메시지를 보내면 주소가 바뀌어 쿼리가 사라지므로, 처음 받은 값만 쓰고 목록을 다시 읽지 않는다.
  const requestedAgent = useRef(agentCodeParam(useSearchParams().get("agent")));
  const { newConversationVersion } = useConversations();
  const [agents, setAgents] = useState<AgentView[]>([]);
  const [agentsLoading, setAgentsLoading] = useState(true);
  const [agentCode, setAgentCode] = useState<string>("");
  /** `generation` 은 `ConversationSession` 의 `key` 다. 올리면 대화별 상태가 모두 새로 만들어진다 */
  const [session, setSession] = useState({
    generation: 0,
    initialConversationId,
  });
  const [conversationId, setConversationId] = useState<string | null>(
    initialConversationId,
  );
  const [seenInitialId, setSeenInitialId] = useState(initialConversationId);
  const [seenPathname, setSeenPathname] = useState(pathname);
  const [seenNewVersion, setSeenNewVersion] = useState(newConversationVersion);

  useEffect(() => {
    fetchChatAgents()
      .then((response) => (response.ok ? response.json() : []))
      .then((data: AgentView[]) => {
        setAgents(data);
        const requested = data.find(
          (agent) => agent.code === requestedAgent.current,
        );
        setAgentCode(
          (current) => current || requested?.code || data[0]?.code || "",
        );
      })
      .catch(() => setAgents([]))
      .finally(() => setAgentsLoading(false));
  }, []);

  const currentConversation = useConversation(conversationId);
  // 대화 줄이 바뀌면 그리는 중에 그 대화의 에이전트로 맞춘다. 처음 그릴 때 이미 줄이 있으면 그때도 맞춘다.
  const [seenConversation, setSeenConversation] =
    useState<typeof currentConversation>(undefined);
  if (seenConversation !== currentConversation) {
    setSeenConversation(currentConversation);
    if (currentConversation) setAgentCode(currentConversation.agentCode ?? "");
  }

  function startNewConversation() {
    setSession((previous) => ({
      generation: previous.generation + 1,
      initialConversationId: null,
    }));
    setConversationId(null);
    setAgentCode(agents[0]?.code ?? "");
  }

  if (seenInitialId !== initialConversationId) {
    setSeenInitialId(initialConversationId);
    if (initialConversationId !== null) {
      setSession((previous) => ({
        generation: previous.generation + 1,
        initialConversationId,
      }));
      setConversationId(initialConversationId);
    }
  }
  if (seenPathname !== pathname) {
    setSeenPathname(pathname);
    // 주소만 `/chat/{id}` 로 바꿔 둔 화면에서 「새 대화」 를 눌렀다.
    if (seenPathname !== "/" && pathname === "/" && conversationId !== null)
      startNewConversation();
  }
  if (seenNewVersion !== newConversationVersion) {
    setSeenNewVersion(newConversationVersion);
    startNewConversation();
  }

  return (
    <ConversationSession
      key={session.generation}
      initialConversationId={session.initialConversationId}
      conversationId={conversationId}
      onConversationIdChange={setConversationId}
      agents={agents}
      agentsLoading={agentsLoading}
      agentCode={agentCode}
      onAgentCodeChange={setAgentCode}
      currentConversation={currentConversation}
    />
  );
}
