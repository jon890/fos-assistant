import {
  connectionCall,
  type BindingStatus,
  type ConnectionStatus,
} from "@/lib/connection";

/**
 * 에이전트 상세의 「이 에이전트가 쓰는 연결」 한 줄이다.
 * `status` 는 붙어 있지 않으면 null 이다. `toolCount` 는 커넥터가 선언한 도구 수이고 `skills` 는 붙이면 설치되는 스킬 이름이다.
 */
export type AgentConnectionView = {
  connectorId: string;
  title: string;
  connectionStatus: ConnectionStatus;
  bound: boolean;
  status: BindingStatus | null;
  restartRequired: boolean;
  toolCount: number;
  skills: string[];
};

/** 붙일 수 없는 에이전트의 까닭이다. 그룹 공개 에이전트와 예전 방식의 연결 에이전트다. */
export type AgentConnectionBlock = "AGENT_NOT_PRIVATE" | "LEGACY_AGENT";

export type AgentConnectionsList = {
  connections: AgentConnectionView[];
  blockedReason: AgentConnectionBlock | null;
};

/**
 * 셸이나 파일에 닿는 도구다. 실행 공간 격리를 적용하지 않은 profile 에서 켜면 붙인 연결의 비밀값을 읽고 연결 도구의 승인 없이 그
 * 서비스를 부를 수 있다. 격리한 profile 에서도 읽은 내용을 인터넷으로 보낼 수 있다(ADR-086).
 */
export const SHELL_OR_FILE_TOOLSETS = ["terminal", "file", "code_execution"];

export function hasShellOrFileTool(enabled: readonly string[]): boolean {
  return enabled.some((name) => SHELL_OR_FILE_TOOLSETS.includes(name));
}

/** 줄의 상태를 화면 말로 바꾼다. 붙었지만 아직 쓸 수 있다고 확인되지 않았으면 반영 대기다. */
export function agentConnectionLabel(view: AgentConnectionView): string {
  if (!view.bound) return "붙지 않음";
  return view.status === "READY" && !view.restartRequired
    ? "붙음"
    : "반영 대기";
}

export const listAgentConnections = (code: string) =>
  connectionCall<AgentConnectionsList>(`/api/agents/${code}/connections`);

export const bindAgentConnection = (code: string, connectorId: string) =>
  connectionCall<AgentConnectionView>(
    `/api/agents/${code}/connections/${connectorId}`,
    { method: "PUT" },
  );

export const unbindAgentConnection = (code: string, connectorId: string) =>
  connectionCall<null>(`/api/agents/${code}/connections/${connectorId}`, {
    method: "DELETE",
  });
