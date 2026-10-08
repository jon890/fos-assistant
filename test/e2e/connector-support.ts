/**
 * 연결을 에이전트에 붙여 커넥터 도구를 부르는 시나리오들이 함께 쓰는 준비다.
 *
 * <p>연결 등록, 비공개 에이전트 만들기, 붙이기, 반영 확인을 차례로 한다(ADR-083). 새 서버를 더한 붙이기는 재시작이 필요 없어
 * Control Plane 이 반영 지연 뒤 스스로 확인해 `READY` 로 만든다(ADR-20261007 / connector-live-reload). 값 교체처럼 재시작 대기가 된
 * 바인딩만 관리자인 dad 가 반영 완료를 눌러야 한다. 만든 것은 {@link ConnectorSetup} 이 적어 두고 시나리오 끝에서 되돌린다.
 */
import { call, expect, expectStatus, fail, type Context } from "./harness.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK } from "./fake-hermes.ts";

export type BoundAgentView = { agentCode: string; agentName: string; status: string; restartRequired: boolean };
export type ConnectionView = {
  connectorId: string;
  status: string;
  secretPrefixes: Record<string, string>;
  values: Record<string, string>;
  checkedAt: string | null;
  bindings: BoundAgentView[];
  undeclaredTools: number;
};
export type AgentConnectionView = {
  connectorId: string;
  title: string;
  connectionStatus: string;
  bound: boolean;
  status: string | null;
  restartRequired: boolean;
  toolCount: number;
  skills: string[];
};
export type AgentConnectionsView = { connections: AgentConnectionView[]; blockedReason: string | null };
export type AdminConnectionView = {
  connectorId: string;
  userId: number;
  displayName: string;
  agentCode: string;
  status: string;
  restartRequired: boolean;
  restartRequiredSince: string | null;
  undeclaredTools: number;
};
export type AgentRef = { code: string; profile: string };
export type ToolProbe = { answer: string; conversationId: string };

/** 시험 커넥터에 넣는 값이다. 칸이 없는 커넥터는 빈 값을 받는다. */
export const DEMO_VALUES = { token: DEMO_TOKEN_OK, scope: "a" };

export function connectionPath(connectorId: string): string {
  return `/connections/${connectorId}`;
}

/** 대역의 hook 이 판정을 물을 Control Plane 주소를 준다. */
export function useConnectorPolicy(context: Context): void {
  context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
}

/** 연결을 등록한다. 확인 도구를 통과하면 바로 `READY` 다. */
export async function connect(
  context: Context,
  token: string,
  connectorId: string = DEMO_CONNECTOR.id,
  values: Record<string, string> = connectorId === DEMO_CONNECTOR.id ? DEMO_VALUES : {},
): Promise<ConnectionView> {
  const connected = expectStatus(
    await call(context, connectionPath(connectorId), { method: "POST", token, body: { values } }),
    200,
    `${connectorId} 연결 등록`,
  ).json<ConnectionView>();
  expect(connected.status === "READY", `${connectorId} 연결이 READY 가 아니다: ${JSON.stringify(connected)}`);
  return connected;
}

/** 비공개 에이전트를 만들고 그 profile 이름을 관리 목록에서 읽는다. */
export async function createPrivateAgent(context: Context, token: string, name: string): Promise<AgentRef> {
  const created = expectStatus(
    await call(context, "/agents", { method: "POST", token, body: { name } }),
    201,
    `에이전트 ${name} 만들기`,
  ).json<{ code: string; visibility: string }>();
  expect(created.visibility === "PRIVATE", `만든 에이전트가 비공개가 아니다: ${JSON.stringify(created)}`);
  const profile = expectStatus(
    await call(context, "/admin/agents", { token: context.tokens.dad }),
    200,
    "관리 목록",
  ).json<{ code: string; hermesProfile: string }[]>().find((agent) => agent.code === created.code)?.hermesProfile;
  if (profile === undefined) fail(`관리 목록에 만든 에이전트 ${created.code} 가 없다`);
  return { code: created.code, profile };
}

/** 내 연결을 내 에이전트에 붙인다. 붙인 바인딩은 반영이 확인될 때까지 `PENDING` 이다. */
export async function bind(context: Context, token: string, agentCode: string, connectorId: string): Promise<AgentConnectionView> {
  return expectStatus(
    await call(context, `/agents/${agentCode}/connections/${connectorId}`, { method: "PUT", token }),
    200,
    `${agentCode} 에 ${connectorId} 붙이기`,
  ).json<AgentConnectionView>();
}

/** 관리자가 그 바인딩의 반영 완료를 누른다. 관리자 목록에서 본 재시작 대기 시각을 그대로 보낸다. */
export async function confirm(context: Context, agentCode: string, connectorId: string): Promise<AgentConnectionView> {
  const row = expectStatus(
    await call(context, "/admin/connections", { token: context.tokens.dad }),
    200,
    "관리자 연결 목록",
  ).json<AdminConnectionView[]>().find((item) => item.agentCode === agentCode && item.connectorId === connectorId);
  if (row === undefined) fail(`관리자 연결 목록에 ${agentCode} 의 ${connectorId} 가 없다`);
  const confirmed = expectStatus(
    await call(context, `/admin/agents/${agentCode}/connections/${connectorId}/confirm`, {
      method: "POST", token: context.tokens.dad, body: { restartRequiredSince: row.restartRequiredSince },
    }),
    200,
    `${agentCode} 의 ${connectorId} 반영 완료`,
  ).json<AgentConnectionView>();
  expect(
    confirmed.bound && confirmed.status === "READY" && !confirmed.restartRequired,
    `반영 완료 뒤 바인딩이 READY 가 아니다: ${JSON.stringify(confirmed)}`,
  );
  return confirmed;
}

/** 붙이고 Control Plane 이 스스로 확인해 `READY` 가 될 때까지 기다린다. 관리자 반영 완료는 누르지 않는다. */
export async function attach(context: Context, token: string, agentCode: string, connectorId: string): Promise<void> {
  const bound = await bind(context, token, agentCode, connectorId);
  expect(
    bound.bound && bound.status === "PENDING" && !bound.restartRequired,
    `붙인 바인딩이 재시작이 필요 없는 PENDING 이 아니다: ${JSON.stringify(bound)}`,
  );
  await awaitReady(context, token, agentCode, connectorId);
}

/** 그 바인딩이 관리자 반영 완료 없이 `READY` 가 될 때까지 40초 안에서 기다린다. */
export async function awaitReady(context: Context, token: string, agentCode: string, connectorId: string): Promise<AgentConnectionView> {
  const deadline = Date.now() + 40_000;
  let last: AgentConnectionView | undefined;
  while (Date.now() < deadline) {
    const listed = expectStatus(
      await call(context, `/agents/${agentCode}/connections`, { token }),
      200,
      "에이전트의 연결 목록",
    ).json<AgentConnectionsView>();
    last = listed.connections.find((connection) => connection.connectorId === connectorId);
    if (last?.bound === true && last.status === "READY" && !last.restartRequired) return last;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  return fail(`${agentCode} 의 ${connectorId} 가 40초 안에 READY 가 되지 않았다: ${JSON.stringify(last)}`);
}

/**
 * 에이전트에게 커넥터 도구 호출 한 줄을 시켜 대역이 답한 판정 줄과 그 대화를 돌려준다.
 *
 * <p>답은 `allow` 나 `block <글>` 이다. 대역이 그 에이전트 profile 의 hook 처럼 Control Plane 에 판정을 묻는다.
 */
export async function probeTool(
  context: Context,
  token: string,
  agentCode: string,
  hermesTool: string,
  argsJson = "{}",
): Promise<ToolProbe> {
  const turn = expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token,
      body: { text: `${CONNECTOR_TOOL_PROBE}\n${hermesTool} ${argsJson}`, agentCode },
    }),
    200,
    `${hermesTool} 호출 대화`,
  ).json<{ conversationId: string; assistantText: string }>();
  const line = turn.assistantText.split("\n").find((candidate) => candidate.startsWith(`${hermesTool}: `));
  if (line === undefined) fail(`답에 ${hermesTool} 의 판정 줄이 없다: ${turn.assistantText}`);
  return { answer: line.slice(`${hermesTool}: `.length), conversationId: turn.conversationId };
}

/** 판정 글에서 승인 요청 번호를 꺼낸다. */
export function requestNumber(answer: string): string | undefined {
  return /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/.exec(answer)?.[0];
}

/**
 * 한 사용자가 시나리오에서 만든 연결과 에이전트를 적어 두고 끝에서 되돌린다.
 *
 * <p>준비가 중간에 실패해도 만든 것까지만 지운다. 에이전트를 먼저 지워 바인딩을 떼고, 그 뒤 연결을 해제해 보관 파일을 지운다.
 */
export class ConnectorSetup {
  readonly #context: Context;
  readonly #token: string;
  readonly agents: AgentRef[] = [];
  readonly connected: string[] = [];

  constructor(context: Context, token: string) {
    this.#context = context;
    this.#token = token;
  }

  async connect(connectorId: string = DEMO_CONNECTOR.id): Promise<ConnectionView> {
    const connected = await connect(this.#context, this.#token, connectorId);
    if (!this.connected.includes(connectorId)) this.connected.push(connectorId);
    return connected;
  }

  async createAgent(name: string): Promise<AgentRef> {
    const agent = await createPrivateAgent(this.#context, this.#token, name);
    this.agents.push(agent);
    return agent;
  }

  /** 연결을 등록하고 비공개 에이전트를 만들어 그 연결들을 붙이고 반영 완료까지 한다. */
  async attachedAgent(name: string, connectorIds: readonly string[] = [DEMO_CONNECTOR.id]): Promise<AgentRef> {
    for (const connectorId of connectorIds) await this.connect(connectorId);
    const agent = await this.createAgent(name);
    for (const connectorId of connectorIds) await attach(this.#context, this.#token, agent.code, connectorId);
    return agent;
  }

  /** 시나리오가 이미 해제한 연결을 목록에서 뺀다. */
  forget(connectorId: string): void {
    const index = this.connected.indexOf(connectorId);
    if (index >= 0) this.connected.splice(index, 1);
  }

  /** 만든 것을 되돌린다. 시나리오가 이미 실패했으면 정리 실패로 그 실패를 가리지 않는다. */
  async cleanUp(failed: boolean): Promise<void> {
    const errors: string[] = [];
    for (const agent of this.agents) {
      const deleted = await call(this.#context, `/agents/${agent.code}`, { method: "DELETE", token: this.#token })
        .catch((error: unknown) => ({ status: 0, body: String(error) }));
      if (deleted.status !== 204) errors.push(`에이전트 ${agent.code} 지우기: ${deleted.status} ${deleted.body}`);
    }
    for (const connectorId of this.connected) {
      const disconnected = await call(this.#context, connectionPath(connectorId), { method: "DELETE", token: this.#token })
        .catch((error: unknown) => ({ status: 0, body: String(error) }));
      if (disconnected.status !== 200) errors.push(`${connectorId} 해제: ${disconnected.status} ${disconnected.body}`);
    }
    if (!failed && errors.length > 0) fail(`정리하지 못했다: ${errors.join("; ")}`);
  }
}
