/** 메시지 읽기 응답의 항목이다. 판정에 쓰는 칸만 적는다. */
export type MessageView = {
  role: string;
  executionId: number | null;
  status: string | null;
  artifacts: { path: string; deleted: boolean }[];
};
/** 실행 트리의 노드다. 판정에 쓰는 칸만 적는다. */
export type ExecutionNode = {
  executionId: number;
  status: string;
  events: { toolName: string | null; failed: boolean | null }[];
  children: ExecutionNode[];
};
export type ExecutionTree = { root: ExecutionNode; truncated: boolean };

/**
 * 평가가 부르는 Control Plane API 다. 요청마다 시간 제한을 건다.
 * 실패하면 상태 코드와 응답 본문의 `code` 만 담은 오류를 던진다. 응답 본문 전체와 토큰은 오류 문구에 넣지 않는다.
 */
export class ControlPlane {
  private readonly baseUrl: string;
  private readonly token: string;
  private readonly timeoutMs: number;

  constructor(baseUrl: string, token: string, timeoutMs: number) {
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.token = token;
    this.timeoutMs = timeoutMs;
  }

  private async request(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` };
    let payload: FormData | string | undefined;
    if (body instanceof FormData) payload = body;
    else if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      payload = JSON.stringify(body);
    }
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: payload,
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) {
      let code = "";
      try {
        const parsed: unknown = await response.json();
        if (parsed && typeof parsed === "object" && typeof (parsed as { code?: unknown }).code === "string")
          code = ` ${(parsed as { code: string }).code}`;
      } catch {
        // 본문이 JSON 이 아니면 상태 코드만 알린다
      }
      throw new Error(`${method} 요청 실패: HTTP ${response.status}${code}`);
    }
    return response;
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    return (await (await this.request(method, path, body)).json()) as T;
  }

  async startConversation(agentCode: string): Promise<string> {
    const body = await this.json<{ conversationId: string }>("POST", "/api/v1/chat/conversations", { agentCode });
    return body.conversationId;
  }

  async chooseTier(conversationId: string, tier: string): Promise<void> {
    await this.request("PUT", `/api/v1/chat/conversations/${conversationId}/model-tier`, { mode: "TIER", tier });
  }

  async upload(conversationId: string, name: string, bytes: Uint8Array): Promise<number> {
    const form = new FormData();
    form.append("file", new Blob([bytes], { type: "image/jpeg" }), name);
    const body = await this.json<{ id: number }>("POST", `/api/v1/chat/conversations/${conversationId}/attachments`, form);
    return body.id;
  }

  async send(
    conversationId: string,
    text: string,
    agentCode: string,
    attachmentIds: number[],
  ): Promise<{ executionId: number | null; assistantText: string | null }> {
    return this.json("POST", "/api/v1/chat/messages", { conversationId, text, agentCode, attachmentIds });
  }

  async messages(conversationId: string): Promise<MessageView[]> {
    return this.json("GET", `/api/v1/chat/conversations/${conversationId}/messages`);
  }

  async file(conversationId: string, path: string): Promise<string> {
    const encoded = path.split("/").map(encodeURIComponent).join("/");
    return (await this.request("GET", `/api/v1/chat/conversations/${conversationId}/files/${encoded}`)).text();
  }

  async tree(executionId: number): Promise<ExecutionTree> {
    return this.json("GET", `/api/v1/usage/executions/${executionId}/tree`);
  }

  async deleteConversation(conversationId: string): Promise<void> {
    await this.request("DELETE", `/api/v1/chat/conversations/${conversationId}`);
  }
}
