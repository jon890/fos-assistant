import { type FakeHermesState, type ConnectorCall } from "./state.ts";
import { FAKE_CONNECTORS } from "./connectors.ts";
import { signedPolicyRequest, signedCallContext, signedSubagentRegistration } from "../mcp-context.ts";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
/** profile hook 처럼 Control Plane 정책과 MCP 도구를 부른다. */
export function createMcp(state: FakeHermesState) {

  /**
   * profile 플러그인의 `pre_tool_call` hook 처럼 커넥터 도구 호출마다 Control Plane 에 판정을 묻는다.
   *
   * <p>토큰은 그 profile 의 `.env` 에 든 MCP 토큰이고, 제출받은 run 의 session 이 곧 루트 session 이다.
   * 실제 hook 과 같이 주소나 토큰이 없거나 답이 200 의 `allow` 가 아니면 막는다. `block` 에 글이 없어도 막는다.
   * 인자는 입력의 글을 그대로 보낸다. 실제 hook 은 키를 정렬해 직렬화하지만 서버는 받은 글을 그대로 해시한다.
   * 원래 도구 이름은 등록 이름의 서버 앞부분으로 고른 커넥터가 선언한 도구일 때만 싣는다.
   *
   * @param callIdPrefix 도구 호출 id 의 앞부분. 한 session 에서 같은 id 를 다시 쓰면 앞선 판정이 되풀이되므로 부를 때마다 다르게 준다
   * @param allowed 주면 허용된 호출을 여기에 더한다. 실행의 사건 스트림이 그 호출을 도구 사건으로 흘린다
   */
  const judgeConnectorCalls = async (
    profile: string,
    token: string | undefined,
    sessionId: string | undefined,
    lines: readonly string[],
    callIdPrefix: string,
    allowed?: ConnectorCall[],
  ): Promise<string> => {
    const output: string[] = [];
    for (const [index, line] of lines.entries()) {
      const space = line.indexOf(" ");
      const hermesTool = space < 0 ? line : line.slice(0, space);
      const argsJson = space < 0 ? "{}" : line.slice(space + 1).trim();
      const connector = FAKE_CONNECTORS.find((candidate) => hermesTool.startsWith(`mcp__${candidate.mcp_server}__`));
      const original = connector === undefined ? "" : hermesTool.slice(`mcp__${connector.mcp_server}__`.length);
      const tool = connector !== undefined && Object.hasOwn(connector.tools, original) ? original : null;
      let blocked = "정책을 확인하지 못했다";
      if (token !== undefined && state.connectorPolicyEndpoint !== undefined && sessionId !== undefined) {
        const response = await fetch(state.connectorPolicyEndpoint, {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify(signedPolicyRequest(
            token, hermesTool, tool, sessionId, sessionId, `${callIdPrefix}-${index + 1}`, argsJson,
          )),
        });
        if (response.status === 200) {
          const answer = await response.json() as { decision?: unknown; message?: unknown };
          if (answer.decision === "allow") {
            state.connectorToolCalls.push({ profile, hermesTool, argsJson, via: "hook" });
            allowed?.push({ hermesTool, argsJson });
            output.push(`${hermesTool}: allow`);
            continue;
          }
          if (answer.decision === "block" && typeof answer.message === "string" && answer.message.trim() !== "") {
            blocked = answer.message;
          }
        } else {
          blocked = `정책을 확인하지 못했다 (HTTP ${response.status})`;
        }
      }
      output.push(`${hermesTool}: block ${blocked}`);
    }
    return output.join("\n");
  };

  /**
   * profile 플러그인처럼 서명한 `_fos_ctx` 를 붙여 Control Plane MCP 도구 하나를 부르고 도구 결과의 text 를 돌려준다.
   *
   * <p>제출받은 run 의 session 이 곧 루트 session 이다. run 마다 자기 session 으로 서명하므로, 나란히 도는 두 run 이
   * 서로의 session 을 쓰면 요청자가 뒤섞여 검사가 실패한다. 하위 에이전트는 루트와 자기 session 을 따로 준다.
   * 주소와 토큰은 `setMemoryReadMcp` 로 받은 것을 쓴다.
   */
  const callControlPlaneToolViaMcp = async (
    name: string,
    args: Record<string, unknown>,
    sessionId: string | undefined,
    rootSessionId: string | undefined = sessionId,
  ): Promise<string> => {
    if (state.memoryReadMcp === undefined) throw new Error(`${name} MCP runtime is not configured`);
    if (sessionId === undefined || rootSessionId === undefined) {
      throw new Error(`${name} MCP needs the submitted session_id to sign _fos_ctx`);
    }
    const _fos_ctx = signedCallContext(state.memoryReadMcp.token, name, rootSessionId, sessionId, `call_${randomUUID()}`);
    const response = await fetch(state.memoryReadMcp.endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${state.memoryReadMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: { ...args, _fos_ctx } } }),
    });
    if (!response.ok) throw new Error(`${name} MCP HTTP ${response.status}`);
    const body = await response.json() as { result?: { content?: { text?: string }[] } };
    const text = body.result?.content?.[0]?.text;
    if (text === undefined) throw new Error(`${name} MCP response has no text`);
    return text;
  };

  /** 서명한 `_fos_ctx` 를 붙여 `memory_read` 를 부르고 도구 결과의 text 를 돌려준다. */
  const readMemoryViaMcp = (
    memoryId: number,
    sessionId: string | undefined,
    rootSessionId: string | undefined = sessionId,
  ): Promise<string> => callControlPlaneToolViaMcp("memory_read", { id: memoryId }, sessionId, rootSessionId);

  /**
   * profile 플러그인의 `subagent_start` hook 처럼 자식 session 을 부모의 루트 아래 등록한다.
   *
   * <p>실제 hook 은 예외를 삼키므로 응답이 2xx 가 아니어도 던지지 않고 상태만 남긴다. 시나리오가 그 기록으로 실패를 안다.
   * 등록 경로는 `/mcp` 와 같은 서버에 있어 MCP 주소에서 `/mcp` 를 떼어 만든다.
   */
  const registerSubagent = async (rootSessionId: string | undefined): Promise<string> => {
    if (state.memoryReadMcp === undefined) throw new Error("subagent registration needs the MCP token");
    if (rootSessionId === undefined) throw new Error("subagent registration needs the submitted session_id");
    const childSessionId = `native-${randomUUID()}`;
    const origin = state.memoryReadMcp.endpoint.replace(/\/mcp$/, "");
    const response = await fetch(`${origin}/internal/hermes/session-bindings/subagent`, {
      method: "POST",
      headers: { Authorization: `Bearer ${state.memoryReadMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify(signedSubagentRegistration(state.memoryReadMcp.token, rootSessionId, rootSessionId, childSessionId)),
    });
    state.subagentRegistrations.push({ childSessionId, rootSessionId, status: response.status });
    return childSessionId;
  };

  /**
   * profile 플러그인처럼 도구 인자에 서명한 `_fos_ctx` 를 붙여 `artifact_write` 를 부른다.
   *
   * <p>제출받은 run 의 session 이 곧 루트 session 이다. 하위 에이전트가 아니므로 session 과 루트가 같다.
   */
  const writeArtifactViaMcp = async (conversationId: string, sessionId: string | undefined): Promise<void> => {
    if (state.artifactWriteMcp === undefined) throw new Error("artifact_write MCP runtime is not configured");
    if (sessionId === undefined) throw new Error("artifact_write MCP needs the submitted session_id to sign _fos_ctx");
    const token = state.artifactWriteMcp.token;
    const request = async (body: unknown): Promise<unknown> => {
      const response = await fetch(state.artifactWriteMcp.endpoint, {
        method: "POST",
        headers: { Authorization: `Bearer ${state.artifactWriteMcp.token}`, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error(`artifact_write MCP HTTP ${response.status}`);
      return response.json();
    };
    await request({ jsonrpc: "2.0", id: 1, method: "initialize" });
    const notification = await fetch(state.artifactWriteMcp.endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${state.artifactWriteMcp.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    });
    if (notification.status !== 202) throw new Error(`artifact_write MCP notification ${notification.status}`);
    const listed = await request({ jsonrpc: "2.0", id: 2, method: "tools/list" }) as { result?: { tools?: { name?: string }[] } };
    if (!listed.result?.tools?.some((tool) => tool.name === "artifact_write")) throw new Error("artifact_write MCP tool was not discovered");
    for (const [path, content] of [["test/index.html", "<!doctype html><title>MCP 초안</title><h1>MCP 결과물</h1>"], ["test/style.css", "h1 { color: navy; }"]] as const) {
      const _fos_ctx = signedCallContext(token, "artifact_write", sessionId, sessionId, `call_${randomUUID()}`);
      const result = await request({ jsonrpc: "2.0", id: path, method: "tools/call", params: { name: "artifact_write", arguments: { conversation_id: conversationId, path, content, _fos_ctx } } }) as { result?: { content?: { text?: string }[]; isError?: boolean } };
      if (result.result?.isError === true || result.result?.content?.[0]?.text === undefined) throw new Error("artifact_write MCP call failed");
      const written = JSON.parse(result.result.content[0].text) as { path?: string; byteSize?: number };
      if (written.path !== path || typeof written.byteSize !== "number") throw new Error("artifact_write MCP response is invalid");
    }
  };
  return { judgeConnectorCalls, callControlPlaneToolViaMcp, readMemoryViaMcp, registerSubagent, writeArtifactViaMcp };
}
