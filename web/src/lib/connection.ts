export type ConnectionStatus = "DISCONNECTED" | "PENDING" | "READY";

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

/** 연결 목록의 카드 한 장이다. `available` 이 거짓이면 운영 목록에서 빠진 커넥터다. */
export type ConnectorSummary = {
  id: string;
  title: string;
  description: string;
  fields: ConnectorField[];
  myStatus: ConnectionStatus;
  available: boolean;
};

export type ConnectorOption = { value: string; label: string };

/** 현재 사용자의 연결 상태다. 비밀 원문은 이 형식에 없고 앞부분만 있다. */
export type ConnectorConnection = {
  connectorId: string;
  status: ConnectionStatus;
  secretPrefixes: Record<string, string>;
  values: Record<string, string>;
  checkedAt: string | null;
  agentCode: string | null;
  restartRequired: boolean;
};

/** 관리자가 반영 완료를 확인할 수 있는 다른 사용자의 연결이다. */
export type AdminConnection = {
  connectorId: string;
  userId: number;
  displayName: string | null;
  status: ConnectionStatus;
  agentCode: string | null;
  restartRequired: boolean;
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

export type ConnectionCallResult<T> =
  { ok: true; data: T } | { ok: false; code: string; message: string };

async function call<T>(
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

export const readConnectors = () => call<ConnectorSummary[]>("/api/connectors");

export const readConnection = (id: string) =>
  call<ConnectorConnection>(`/api/connections/${id}`);

export const readOptions = (
  id: string,
  fieldKey: string,
  values: Record<string, string>,
) =>
  call<ConnectorOption[]>(`/api/connections/${id}/options/${fieldKey}`, {
    method: "POST",
    body: { values },
  });

export const registerConnection = (
  id: string,
  values: Record<string, string>,
) =>
  call<ConnectorConnection>(`/api/connections/${id}`, {
    method: "POST",
    body: { values },
  });

export const checkConnection = (id: string) =>
  call<ConnectorConnection>(`/api/connections/${id}/check`, { method: "POST" });

export const disconnectConnection = (id: string) =>
  call<ConnectorConnection>(`/api/connections/${id}`, { method: "DELETE" });

export const readAdminConnections = () =>
  call<AdminConnection[]>("/api/admin/connections");

export const confirmAdminConnection = (id: string, userId: number) =>
  call<AdminConnection>(`/api/admin/connections/${id}/${userId}/confirm`, {
    method: "POST",
  });

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
