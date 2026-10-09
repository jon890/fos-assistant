export type ConnectionStatus = "DISCONNECTED" | "PENDING" | "READY";

/**
 * 에이전트에 붙인 연결의 상태다. 붙인 직후는 반영이 확인될 때까지 `PENDING` 이다.
 * 재시작이 필요 없으면 Control Plane 이 스스로 확인하고, 재시작 대기면 관리자 반영 완료가 `READY` 로 만든다.
 */
export type BindingStatus = "PENDING" | "READY";

/** 연결이 붙은 에이전트 하나다. `restartRequired` 면 관리자가 공유 gateway 를 재시작한 뒤 반영 완료를 눌러야 한다. */
export type BoundAgent = {
  agentCode: string;
  agentName: string;
  status: BindingStatus;
  restartRequired: boolean;
};

/** 커넥터 manifest 의 칸 한 개다. 입력 칸은 이 목록으로 그린다. */
export type ConnectorField = {
  key: string;
  label: string;
  description: string | null;
  secret: boolean;
  required: boolean;
  pattern: string | null;
  hasOptions: boolean;
  autoSelectSingle: boolean;
};

export type ToolRisk =
  "READ" | "SENSITIVE" | "WRITE" | "DESTRUCTIVE" | "FINANCIAL";
export type ToolApproval = "NONE" | "REQUIRED" | "ALWAYS";

/**
 * 커넥터가 선언한 도구 한 개다. `title` 이 없으면 `name` 으로 보인다.
 * `grant` 는 승인하면서 그 도구에 상시 허락을 줄 수 있는지다.
 */
export type ConnectorTool = {
  name: string;
  title: string | null;
  risk: ToolRisk;
  approval: ToolApproval;
  grant: boolean;
};

/** 연결 목록의 카드 한 장이다. `available` 이 거짓이면 운영 목록에서 빠진 커넥터다. */
export type ConnectorSummary = {
  id: string;
  title: string;
  description: string;
  /** `data:image/svg+xml` 나 `data:image/png` 의 base64 글이다. 옛 Control Plane 은 내지 않는다. */
  icon: string | null;
  /** 서비스 소개 주소(`https://`)다. 옛 Control Plane 은 내지 않는다. */
  link: string | null;
  fields: ConnectorField[];
  tools: ConnectorTool[];
  myStatus: ConnectionStatus;
  available: boolean;
  /** 이 연결을 붙인 내 에이전트들이다. */
  bindings: BoundAgent[];
  /** 「내 브라우저」 에서 먼저 로그인할 `https://` 주소다. 선언하지 않았으면 null 이다. 옛 Control Plane 은 내지 않는다. */
  ownerBrowserLoginUrl: string | null;
};

export type ConnectorOption = { value: string; label: string };

/** 현재 사용자의 연결 상태다. 비밀 원문은 이 형식에 없고 앞부분만 있다. */
export type ConnectorConnection = {
  connectorId: string;
  status: ConnectionStatus;
  secretPrefixes: Record<string, string>;
  values: Record<string, string>;
  checkedAt: string | null;
  /** 이 연결을 붙인 에이전트들이다. 재시작 대기는 연결이 아니라 붙은 에이전트마다 있다. */
  bindings: BoundAgent[];
  /** 커넥터가 선언하지 않아 쓰지 않는 도구의 수다. */
  undeclaredTools: number;
};

/**
 * 관리자가 반영 완료를 누를 수 있는 같은 그룹 사용자의 바인딩 한 줄이다.
 * `restartRequiredSince` 는 반영 완료 요청에 그대로 돌려보낸다.
 */
export type AdminConnection = {
  connectorId: string;
  userId: number;
  displayName: string | null;
  agentCode: string;
  status: BindingStatus;
  restartRequired: boolean;
  restartRequiredSince: string | null;
  undeclaredTools: number;
};

export const CONNECTOR_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;
export const FIELD_KEY_PATTERN = /^[a-z][a-z0-9_]{0,31}$/;

/** 브라우저와 서버 라우트가 같이 쓰는 오류 문구다. Control Plane 의 오류 원문은 쓰지 않는다. */
export const CONNECTION_ERROR_MESSAGES: Record<string, string> = {
  CONNECTOR_CREDENTIAL_REJECTED: "입력한 값을 확인하지 못했어요.",
  CONNECTOR_FORBIDDEN: "이 값으로는 쓸 수 없어요.",
  CONNECTOR_UNAVAILABLE: "서비스에 닿지 못했어요. 잠시 뒤 다시 해 주세요.",
  CONNECTOR_OPERATION_FAILED: "연결을 마치지 못했어요.",
  CONNECTOR_NOT_FOUND: "찾을 수 없는 서비스예요.",
  CONNECTOR_RATE_LIMITED: "요청이 많아요. 잠시 뒤 다시 해 주세요.",
  CONNECTOR_ACTION_EXECUTING:
    "승인한 동작을 실행하는 중이에요. 끝난 뒤 다시 시도해 주세요.",
  CONNECTOR_NOT_CONNECTED: "서비스 연결에서 연결을 확인해 주세요.",
  CONNECTOR_PROFILE_NOT_READY:
    "이 에이전트는 아직 연결을 받을 준비가 되지 않았어요. 관리자에게 알려 주세요.",
  CONNECTOR_BIND_CONFLICT: "이 에이전트의 다른 연결이나 스킬과 이름이 겹쳐요.",
  CONNECTOR_SINGLE_BINDING:
    "이 연결은 다른 에이전트에 붙어 있어요. 그 에이전트에서 뗀 뒤 붙여 주세요.",
  AGENT_SANDBOX_UNAVAILABLE:
    "격리된 실행 공간이 준비된 에이전트에만 붙일 수 있어요. 관리자에게 알려 주세요.",
  SKILL_NAME_TAKEN: "이 에이전트의 다른 연결이나 스킬과 이름이 겹쳐요.",
  CONNECTOR_RESTART_AGAIN:
    "재시작한 뒤에 다시 설치됐어요. 한 번 더 재시작한 뒤 눌러 주세요.",
  CONNECTOR_INSTALL_MISMATCH:
    "설치 상태가 맞지 않아요. 서버 로그에서 까닭을 확인해 주세요.",
  CONNECTOR_TOOLS_UNVERIFIED:
    "도구를 확인하지 못했어요. 연결 값과 서비스 상태를 확인해 주세요.",
  CONNECTOR_APPLY_SCHEDULED: "아직 반영 중이에요. 몇 분 뒤 다시 눌러 주세요.",
  AGENT_CONNECTIONS_REQUIRE_PRIVATE: "비공개 에이전트에만 붙일 수 있어요.",
  AGENT_NOT_FOUND: "에이전트가 없거나 이 계정에서 사용할 수 없어요.",
  AGENT_BUSY: "다른 설정 변경이 끝날 때까지 기다린 뒤 다시 시도해 주세요.",
  FORBIDDEN: "이 작업을 관리할 수 없어요.",
  UNAUTHENTICATED: "로그인이 필요해요.",
  VALIDATION_FAILED: "입력 형식을 확인해 주세요.",
};

const FALLBACK_MESSAGE = "요청을 처리하지 못했어요.";

export function connectionErrorMessage(code: string): string {
  return Object.hasOwn(CONNECTION_ERROR_MESSAGES, code)
    ? CONNECTION_ERROR_MESSAGES[code]
    : FALLBACK_MESSAGE;
}

export type ConnectionFailure = { ok: false; code: string; message: string };

export type ConnectionCallResult<T> = { ok: true; data: T } | ConnectionFailure;

/** 「내 브라우저」 에서 그 주소를 여는 화면 주소다. `https://` 주소일 때만 주고 아니면 null 이다. */
export function browserLoginHref(
  url: string | null | undefined,
): string | null {
  return url?.startsWith("https://")
    ? `/browser?url=${encodeURIComponent(url)}`
    : null;
}

export async function connectionCall<T>(
  path: string,
  init?: { method: string; body?: unknown },
): Promise<ConnectionCallResult<T>> {
  try {
    const response = await fetch(path, {
      method: init?.method,
      headers:
        init?.body === undefined
          ? undefined
          : { "Content-Type": "application/json" },
      body: init?.body === undefined ? undefined : JSON.stringify(init.body),
      cache: "no-store",
    });
    const body: unknown = await response.json().catch(() => null);
    if (response.ok) return { ok: true, data: body as T };
    const code =
      typeof body === "object" &&
      body !== null &&
      "code" in body &&
      typeof body.code === "string"
        ? body.code
        : "";
    return { ok: false, code, message: connectionErrorMessage(code) };
  } catch {
    return {
      ok: false,
      code: "CONNECTOR_UNAVAILABLE",
      message: "연결하지 못했어요. 잠시 뒤 다시 해 주세요.",
    };
  }
}

export const readConnectors = () =>
  connectionCall<ConnectorSummary[]>("/api/connectors");

export const readConnection = (id: string) =>
  connectionCall<ConnectorConnection>(`/api/connections/${id}`);

export const readOptions = (
  id: string,
  fieldKey: string,
  values: Record<string, string>,
) =>
  connectionCall<ConnectorOption[]>(
    `/api/connections/${id}/options/${fieldKey}`,
    {
      method: "POST",
      body: { values },
    },
  );

export const registerConnection = (
  id: string,
  values: Record<string, string>,
) =>
  connectionCall<ConnectorConnection>(`/api/connections/${id}`, {
    method: "POST",
    body: { values },
  });

export const checkConnection = (id: string) =>
  connectionCall<ConnectorConnection>(`/api/connections/${id}/check`, {
    method: "POST",
  });

export const disconnectConnection = (id: string) =>
  connectionCall<ConnectorConnection>(`/api/connections/${id}`, {
    method: "DELETE",
  });

export const readAdminConnections = () =>
  connectionCall<AdminConnection[]>("/api/admin/connections");

/** 관리자 반영 완료다. 목록에서 받은 재시작 대기 시작 시각을 그대로 돌려보낸다. */
export const confirmAdminConnection = (connection: AdminConnection) =>
  connectionCall<unknown>(
    `/api/admin/agents/${connection.agentCode}/connections/${connection.connectorId}/confirm`,
    {
      method: "POST",
      body: { restartRequiredSince: connection.restartRequiredSince },
    },
  );

/** 연결 상태를 화면에 보이는 말로 바꾼다. 재시작 대기는 상태보다 앞선다. */
export function connectionStatusLabel(
  status: ConnectionStatus,
  restartRequired = false,
): string {
  if (restartRequired) return "관리자 반영을 기다려요";
  return { DISCONNECTED: "연결 안 됨", PENDING: "준비 중", READY: "연결됨" }[
    status
  ];
}

const TOOL_RISK_LABELS: Record<ToolRisk, string> = {
  READ: "조회",
  SENSITIVE: "민감한 조회",
  WRITE: "쓰기",
  DESTRUCTIVE: "되돌리기 어려운 쓰기",
  FINANCIAL: "결제",
};

export function toolRiskLabel(risk: ToolRisk): string {
  return TOOL_RISK_LABELS[risk];
}

/**
 * 도구를 쓸 수 없으면 참이다. 막힌 위험도이거나, 승인 방식이 `ALWAYS` 라 설치가 모델에게서 뺀 도구다.
 */
export function toolBlocked(tool: ConnectorTool): boolean {
  return (
    tool.risk === "DESTRUCTIVE" ||
    tool.risk === "FINANCIAL" ||
    tool.approval === "ALWAYS"
  );
}

/**
 * 도구를 호출할 때 일어나는 일을 사용자에게 보이는 말로 바꾼다. 쓸 수 없는 도구가 승인 방식보다 앞선다.
 * 상시 허락을 줄 수 없는 도구는 호출마다 승인을 받는다.
 */
export function toolPolicyLabel(tool: ConnectorTool): string {
  if (toolBlocked(tool)) return "아직 쓸 수 없어요";
  if (tool.approval === "NONE") return "바로 실행해요";
  return tool.grant ? "실행 전에 물어봐요" : "실행할 때마다 물어봐요";
}

const ICON_DATA_URL =
  /^data:image\/(svg\+xml|png);base64,[A-Za-z0-9+/]+={0,2}$/;

/**
 * 서버가 검증한 아이콘도 화면이 한 번 더 모양을 본다. 모양이 틀리면 null 이라 기본 아이콘으로 그린다.
 * `<img>` 의 `src` 로만 쓰므로 SVG 안의 스크립트는 실행되지 않는다.
 */
export function connectorIconSrc(
  icon: string | null | undefined,
): string | null {
  return icon && ICON_DATA_URL.test(icon) ? icon : null;
}

/** 로그인 정보가 없는 `https://` 주소만 링크로 쓴다. 그 밖에는 null 이라 링크를 그리지 않는다. */
export function connectorLinkHref(
  link: string | null | undefined,
): string | null {
  if (!link) return null;
  try {
    const url = new URL(link);
    if (url.protocol !== "https:" || url.username || url.password) return null;
    return url.href;
  } catch {
    return null;
  }
}

const TOOL_RISK_ORDER: ToolRisk[] = [
  "READ",
  "SENSITIVE",
  "WRITE",
  "DESTRUCTIVE",
  "FINANCIAL",
];

/** 위험도별 도구 수다. 위험도 순서로 늘어놓고 0개인 것은 뺀다. */
export function toolRiskCounts(
  tools: ConnectorTool[],
): { risk: ToolRisk; label: string; count: number }[] {
  return TOOL_RISK_ORDER.map((risk) => ({
    risk,
    label: toolRiskLabel(risk),
    count: tools.filter((tool) => tool.risk === risk).length,
  })).filter(({ count }) => count > 0);
}
