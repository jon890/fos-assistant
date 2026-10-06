import type { ToolRisk } from "@/lib/connection";

export type ConnectorActionStatus =
  | "PENDING"
  | "EXECUTING"
  | "SUCCEEDED"
  | "FAILED"
  | "UNKNOWN"
  | "REJECTED"
  | "EXPIRED";

export type GrantPeriod = "HOUR" | "TODAY" | "DAYS_30";

export const CONNECTOR_ACTION_STATUSES: readonly ConnectorActionStatus[] = [
  "PENDING",
  "EXECUTING",
  "SUCCEEDED",
  "FAILED",
  "UNKNOWN",
  "REJECTED",
  "EXPIRED",
];

export const GRANT_PERIODS: readonly GrantPeriod[] = [
  "HOUR",
  "TODAY",
  "DAYS_30",
];

/** 승인 줄 한 개다. 카드는 이 응답으로만 그린다. `toolName` 과 `errorCode` 는 내부 값이라 화면에 그리지 않는다. */
export type ConnectorAction = {
  actionId: string;
  connectorId: string;
  toolName: string | null;
  title: string;
  risk: ToolRisk | null;
  status: ConnectorActionStatus;
  argsJson: string | null;
  resultText: string | null;
  errorCode: string | null;
  createdAt: string;
  expiresAt: string | null;
  grantAllowed: boolean;
  /** 가려진 내용이 있어 승인할 수 없는 줄인가. 참이면 승인 단추를 그리지 않는다. */
  hiddenArgs: boolean;
};

/** 묻지 않고 실행하게 허락한 도구 한 개다. 화면에는 `title` 을 보인다. */
export type ConnectorGrant = {
  grantId: number;
  connectorId: string;
  toolName: string;
  title: string | null;
  expiresAt: string;
};

export const ACTION_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 브라우저와 서버 라우트가 같이 쓰는 오류 문구다. Control Plane 의 오류 원문은 쓰지 않는다. */
export const CONNECTOR_ACTION_ERROR_MESSAGES: Record<string, string> = {
  CONNECTOR_ACTION_NOT_PENDING: "이미 처리된 요청이에요.",
  CONNECTOR_ACTION_NOT_FOUND: "요청을 찾지 못했어요.",
};

const FALLBACK_MESSAGE =
  "요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

export function connectorActionErrorMessage(code: string): string {
  return Object.hasOwn(CONNECTOR_ACTION_ERROR_MESSAGES, code)
    ? CONNECTOR_ACTION_ERROR_MESSAGES[code]
    : FALLBACK_MESSAGE;
}

function readableValue(value: unknown): string {
  if (typeof value === "string") return value;
  if (value === null || typeof value !== "object") return String(value);
  return JSON.stringify(value, null, 2);
}

/** JSON 객체로 읽히는 인자만 꺼낸다. 객체가 아니면 `null` 이다. */
function argsObject(argsJson: string | null): Record<string, unknown> | null {
  if (argsJson === null) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(argsJson);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed))
    return null;
  return parsed as Record<string, unknown>;
}

/**
 * 승인 줄의 인자를 사람이 읽을 키와 값으로 바꾼다.
 *
 * <p>JSON 객체로 읽히면 최상위 키마다 한 줄을 준다. 문자열은 그대로, 숫자와 불린은 글로, 객체와 배열은 2칸
 * 들여쓴 JSON 글로 바꾼다. 객체로 읽히지 않으면 `null` 을 주고, 부른 쪽이 원문을 글 한 덩어리로 보인다.
 *
 * <p>값을 여기서 가리지 않는다. 가림은 Control Plane 이 응답에서 한 번만 한다. 두 곳의 규칙이 다르면 화면만 가린
 * 값이 승인될 수 있다.
 */
export function readableArgs(
  argsJson: string | null,
): { key: string; value: string }[] | null {
  const parsed = argsObject(argsJson);
  if (parsed === null) return null;
  return Object.entries(parsed).map(([key, value]) => ({
    key,
    value: readableValue(value),
  }));
}

/** 카드 위에 바로 보이는 인자의 최대 수다. */
const CORE_LIMIT = 2;
/** 위에 바로 보일 수 있는 값의 최대 글자 수다. 넘으면 「자세히」 로 접는다. */
const CORE_LENGTH = 80;

function emptyValue(value: unknown): boolean {
  if (value === null || value === "") return true;
  if (Array.isArray(value)) return value.length === 0;
  return typeof value === "object" && Object.keys(value).length === 0;
}

function shortValue(value: unknown): boolean {
  if (typeof value === "number" || typeof value === "boolean") return true;
  return (
    typeof value === "string" &&
    !/[\r\n]/.test(value) &&
    value.length <= CORE_LENGTH
  );
}

/** 승인 카드에 그릴 인자다. `rows` 는 원래 순서이고 `core` 가 참인 줄을 카드 위에 바로 보인다. */
export type ApprovalArgs = {
  rows: { key: string; value: string; core: boolean }[];
  /** 값이 비어 있는(`null`, 빈 글, 빈 배열, 빈 객체) 인자의 키다. 값을 보일 것이 없어 이름만 보인다. */
  emptyKeys: string[];
};

/**
 * 승인 카드가 위에 보일 인자와 「자세히」 로 접을 인자를 나눈다.
 *
 * <p>커넥터 선언에는 인자의 표시 이름이나 중요도가 없다. 그래서 키는 원래 이름 그대로 두고, 값이 비어 있지 않은
 * 인자 가운데 앞에서부터 한 줄로 짧게 읽히는 값 둘을 위에 보인다. 그런 값이 없으면 모든 인자를 위에 보인다.
 * 무엇을 접을지는 화면이 정하고, 상시 허락을 닫은 도구는 접지 않는다(ADR-065).
 */
export function approvalArgs(argsJson: string | null): ApprovalArgs | null {
  const parsed = argsObject(argsJson);
  if (parsed === null) return null;
  const emptyKeys: string[] = [];
  const rows: ApprovalArgs["rows"] = [];
  let cores = 0;
  for (const [key, value] of Object.entries(parsed)) {
    if (emptyValue(value)) {
      emptyKeys.push(key);
      continue;
    }
    const core = cores < CORE_LIMIT && shortValue(value);
    if (core) cores += 1;
    rows.push({ key, value: readableValue(value), core });
  }
  if (cores === 0) for (const row of rows) row.core = true;
  return { rows, emptyKeys };
}

/**
 * 묶음의 「모두 승인」 에 넣을 수 있는 줄인가.
 *
 * <p>상시 허락을 줄 수 있는 `WRITE` 도구의 기다리는 줄만 넣는다. 그 도구는 이미 기간을 정해 묻지 않게 할 수
 * 있으므로, 화면에 펼친 줄을 한 번에 승인해도 승인의 뜻이 약해지지 않는다. 상시 허락을 닫은 도구, 되돌리기
 * 어렵거나 결제인 위험도, 위험도를 모르는 줄, 가려진 인자가 있는 줄은 한 건씩 승인한다(ADR-087).
 */
export function bulkApprovable(action: ConnectorAction): boolean {
  return (
    action.status === "PENDING" &&
    action.risk === "WRITE" &&
    action.grantAllowed &&
    !action.hiddenArgs
  );
}

export type ConnectorActionCallResult<T> =
  { ok: true; data: T } | { ok: false; code: string; message: string };

async function call<T>(
  path: string,
  init?: { method: string; body?: unknown },
): Promise<ConnectorActionCallResult<T>> {
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
    return { ok: false, code, message: connectorActionErrorMessage(code) };
  } catch {
    return { ok: false, code: "", message: connectorActionErrorMessage("") };
  }
}

export const readConnectorActions = (conversationId: string) =>
  call<ConnectorAction[]>(
    `/api/chat/conversations/${conversationId}/connector-actions`,
  );

export const approveConnectorAction = (
  actionId: string,
  grant: GrantPeriod | null,
) =>
  call<ConnectorAction>(`/api/connector-actions/${actionId}/approve`, {
    method: "POST",
    body: { grant },
  });

export const rejectConnectorAction = (actionId: string) =>
  call<ConnectorAction>(`/api/connector-actions/${actionId}/reject`, {
    method: "POST",
  });

export const readConnectorGrants = () =>
  call<ConnectorGrant[]>("/api/connector-grants");

export const revokeConnectorGrant = (grantId: number) =>
  call<null>(`/api/connector-grants/${grantId}`, { method: "DELETE" });
