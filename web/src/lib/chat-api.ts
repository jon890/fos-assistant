/**
 * 대화 화면이 부르는 요청이다. 응답을 읽고 오류를 보이는 일은 화면이 맡는다.
 *
 * <p>화면이 상태 코드와 오류 본문의 `code` 로 분기하고 스트림 본문을 직접 읽으므로 `Response` 를 그대로 돌려준다.
 */

const JSON_HEADERS = { "Content-Type": "application/json" };

function conversationPath(conversationId: string): string {
  return `/api/chat/conversations/${conversationId}`;
}

/** 대화를 시작할 때 고를 수 있는 에이전트 목록을 읽는다. */
export function fetchChatAgents(): Promise<Response> {
  return fetch("/api/agents");
}

/** `/` 명령으로 부를 수 있는 그 에이전트의 스킬 목록을 읽는다. */
export function fetchCommandSkills(agentCode: string): Promise<Response> {
  return fetch(`/api/agents/${agentCode}/skills`);
}

/** 시작 화면에 띄울 그 에이전트의 추천 질문을 읽는다. */
export function fetchAgentStarters(agentCode: string): Promise<Response> {
  return fetch(`/api/agents/${agentCode}/starters`, { cache: "no-store" });
}

/** 그 에이전트로 고를 수 있는 모델 목록을 읽는다. */
export function fetchModelOptions(agentCode: string): Promise<Response> {
  return fetch(
    `/api/chat/model-options?agentCode=${encodeURIComponent(agentCode)}`,
    { cache: "no-store" },
  );
}

/** 빈 대화를 만든다. */
export function createConversation(agentCode: string): Promise<Response> {
  return fetch("/api/chat/conversations", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ agentCode }),
  });
}

export function renameConversation(
  conversationId: string,
  title: string,
): Promise<Response> {
  return fetch(conversationPath(conversationId), {
    method: "PATCH",
    headers: JSON_HEADERS,
    body: JSON.stringify({ title }),
  });
}

export function deleteConversation(conversationId: string): Promise<Response> {
  return fetch(conversationPath(conversationId), { method: "DELETE" });
}

/** 그 대화에서 지금 도는 turn 이 있는지 묻는다. */
export function fetchRunningTurn(conversationId: string): Promise<Response> {
  return fetch(`${conversationPath(conversationId)}/running`, {
    cache: "no-store",
  });
}

/** 그 대화의 저장된 이력을 읽는다. */
export function fetchConversationMessages(
  conversationId: string,
): Promise<Response> {
  return fetch(`${conversationPath(conversationId)}/messages`);
}

/** 대화 단위 SSE 를 연다. 본문은 화면이 스트림으로 읽는다. */
export function openConversationEvents(
  conversationId: string,
  signal: AbortSignal,
): Promise<Response> {
  return fetch(`${conversationPath(conversationId)}/events`, {
    cache: "no-store",
    signal,
  });
}

/** 스트림 없이 메시지를 보내고 완성된 답을 한 번에 받는다. */
export function sendChatMessage(body: unknown): Promise<Response> {
  return fetch("/api/chat", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
}

/** 메시지를 보내고 답을 SSE 로 받는다. 본문은 화면이 스트림으로 읽는다. */
export function startChatStream(body: unknown): Promise<Response> {
  return fetch("/api/chat/stream", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
}

/** 마지막 답을 다시 만들고 그 답을 SSE 로 받는다. */
export function regenerateLatestAnswer(
  conversationId: string,
): Promise<Response> {
  return fetch(`${conversationPath(conversationId)}/regenerate`, {
    method: "POST",
  });
}

/** 실패하거나 중지한 결과 전달을 저장된 결과만으로 다시 전달하고 그 답을 SSE 로 받는다. */
export function retryDelivery(
  conversationId: string,
  deliveryId: number,
): Promise<Response> {
  return fetch(
    `${conversationPath(conversationId)}/deliveries/${deliveryId}/retry`,
    { method: "POST" },
  );
}

/** 도는 실행을 멈춰 달라고 요청한다. 받아들이면 202 를 준다. */
export function stopExecution(executionId: number): Promise<Response> {
  return fetch(`/api/chat/executions/${executionId}/stop`, {
    method: "POST",
  });
}

export function uploadConversationAttachment(
  conversationId: string,
  form: FormData,
): Promise<Response> {
  return fetch(`${conversationPath(conversationId)}/attachments`, {
    method: "POST",
    body: form,
  });
}

export function deleteConversationAttachment(
  conversationId: string,
  attachmentId: number,
): Promise<Response> {
  return fetch(
    `${conversationPath(conversationId)}/attachments/${attachmentId}`,
    { method: "DELETE" },
  );
}

/** 그 대화에서 쓸 모델 선택을 저장한다. */
export function saveConversationModel(
  conversationId: string,
  choice: unknown,
): Promise<Response> {
  return fetch(`${conversationPath(conversationId)}/model`, {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify(choice),
  });
}
