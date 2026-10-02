/**
 * profile 플러그인이 Control Plane MCP 도구 인자에 붙이는 `_fos_ctx` 를 만든다.
 *
 * <p>계약은 `docs/hermes/fos-ctx.md` 의 「`_fos_ctx` 계약」 이 정한다. key 는 MCP 토큰을 SHA-256 한
 * 소문자 16진수 문자열의 UTF-8 바이트이고, 서명할 글은 `v1`, 도구 이름, 뿌리 session, session, 도구 호출 id 를
 * 줄바꿈 하나로 이은 것이다. 가짜 Hermes 와 시나리오가 함께 쓴다.
 *
 * <p>하위 에이전트 session 등록 본문도 여기서 만든다. 계약은 같은 문서의 「하위 에이전트 session 등록 계약」 이 정한다.
 * key 는 `_fos_ctx` 와 같고 서명할 글만 `v1-subagent`, 뿌리, 부모, 자식 session 을 줄바꿈 하나로 이은 것이다.
 *
 * <p>커넥터 도구 호출의 판정 요청 본문도 여기서 만든다. 계약은 `docs/backend/connector-tool-policy.md` 의 「도구 호출 판정」 이 정한다.
 * key 는 같고 서명할 글은 `v1-connector-policy`, 등록 이름, 뿌리 session, session, 도구 호출 id, 인자 글의 SHA-256 을
 * 줄바꿈 하나로 이은 것이다.
 */
import { createHash, createHmac } from "node:crypto";

export type FosCallContext = {
  v: 1;
  root_session_id: string;
  session_id: string;
  tool_call_id: string;
  sig: string;
};

export function signedCallContext(
  token: string,
  toolName: string,
  rootSessionId: string,
  sessionId: string,
  toolCallId: string,
): FosCallContext {
  const key = createHash("sha256").update(token, "utf8").digest("hex");
  const signed = ["v1", toolName, rootSessionId, sessionId, toolCallId].join("\n");
  const sig = createHmac("sha256", Buffer.from(key, "utf8")).update(signed, "utf8").digest("hex");
  return { v: 1, root_session_id: rootSessionId, session_id: sessionId, tool_call_id: toolCallId, sig };
}

export type SubagentRegistration = {
  v: 1;
  parent_session_id: string;
  parent_root_session_id: string;
  child_session_id: string;
  child_subagent_id: string | null;
  parent_subagent_id: string | null;
  sig: string;
};

/** profile 플러그인이 `subagent_start` hook 에서 보내는 등록 본문이다. 운영 코드를 부르지 않고 따로 서명한다. */
export function signedSubagentRegistration(
  token: string,
  parentRootSessionId: string,
  parentSessionId: string,
  childSessionId: string,
): SubagentRegistration {
  const key = createHash("sha256").update(token, "utf8").digest("hex");
  const signed = ["v1-subagent", parentRootSessionId, parentSessionId, childSessionId].join("\n");
  const sig = createHmac("sha256", Buffer.from(key, "utf8")).update(signed, "utf8").digest("hex");
  return {
    v: 1,
    parent_session_id: parentSessionId,
    parent_root_session_id: parentRootSessionId,
    child_session_id: childSessionId,
    child_subagent_id: null,
    parent_subagent_id: null,
    sig,
  };
}

export type ConnectorPolicyRequest = {
  v: 1;
  root_session_id: string;
  session_id: string;
  tool_call_id: string;
  hermes_tool: string;
  tool: string | null;
  args_json: string;
  sig: string;
};

/**
 * profile 플러그인이 커넥터 도구 호출 전에 보내는 판정 요청 본문이다. 운영 코드를 부르지 않고 따로 서명한다.
 *
 * <p>`tool` 은 서명에 들어가지 않는다. 인자는 받은 글 그대로 보내고 그 글의 해시를 서명한다.
 */
export function signedPolicyRequest(
  token: string,
  hermesTool: string,
  tool: string | null,
  rootSessionId: string,
  sessionId: string,
  toolCallId: string,
  argsJson: string,
): ConnectorPolicyRequest {
  const key = createHash("sha256").update(token, "utf8").digest("hex");
  const argsHash = createHash("sha256").update(argsJson, "utf8").digest("hex");
  const signed = ["v1-connector-policy", hermesTool, rootSessionId, sessionId, toolCallId, argsHash].join("\n");
  const sig = createHmac("sha256", Buffer.from(key, "utf8")).update(signed, "utf8").digest("hex");
  return {
    v: 1,
    root_session_id: rootSessionId,
    session_id: sessionId,
    tool_call_id: toolCallId,
    hermes_tool: hermesTool,
    tool,
    args_json: argsJson,
    sig,
  };
}
