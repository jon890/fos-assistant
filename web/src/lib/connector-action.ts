import type { ToolRisk } from "@/lib/connection";
import type { ContentDraftSummary } from "@/lib/content-draft-snapshot";

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

/** 금융 줄에서 입력을 바꾼 칸 하나다. 없던 값은 null 이다. */
export type FinancialNormalization = {
  field: string;
  before: string | null;
  after: string;
};

/**
 * 금융 승인 줄의 저장 요약이다. `available` 이 거짓이면 저장한 내용을 열지 못한 줄이라 나머지 칸은 null 이고
 * `normalization` 은 비어 있다. `accountSeq` 는 실제로 전송할 계좌 순번이다.
 */
export type FinancialSummary = {
  available: boolean;
  accountSeq: string | null;
  operation: "CREATE" | "CANCEL" | null;
  symbol: string | null;
  market: string | null;
  currency: string | null;
  side: "BUY" | "SELL" | null;
  quantity: string | null;
  orderAmount: string | null;
  price: string | null;
  orderType: "LIMIT" | "MARKET" | null;
  timeInForce: string | null;
  orderId: string | null;
  normalization: FinancialNormalization[];
};

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
  /** 도구별 표시 이름이다. 옛 서버는 이 칸을 내지 않는다. */
  argLabels?: Record<string, string>;
  /** 승인하면서 이 대화에서 계속 허용할 수 있는 줄인가. 밖으로 나가는 도구는 거짓이다. */
  conversationGrantAllowed: boolean;
  /** 원고 저장 job 이 붙은 줄의 요약이다. 일반 줄은 null 이다. */
  contentDraft: ContentDraftSummary | null;
  /** 금융 줄의 저장 요약이다. 금융 줄이 아니면 null 이다. */
  financial: FinancialSummary | null;
};

/** 이 대화에서 계속 허용 중인 도구 한 개다. 화면에는 `title` 을 보인다. */
export type ConversationGrant = {
  grantId: number;
  title: string;
  expiresAt: string;
  /** 대화의 에이전트와 다른 에이전트에만 통하는 허락이면 그 이름이다. 아니면 null 이다. */
  agentName: string | null;
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

/** Control Plane 이 가린 자리에 넣는 글이다. `ToolDetailRedactor` 의 것과 같다. */
const HIDDEN_MARK = "[가림]";

const LABEL_KEY = /^[A-Za-z_][A-Za-z0-9_]{0,30}$/;
const LABEL_RESERVED = new Set(["__proto__", "constructor", "prototype"]);
const LABEL_FORBIDDEN =
  /[\x00-\x1f\x7f-\x9f\u061c\u200e\u200f\u2028\u2029\u202a-\u202e\u2066-\u2069]/;
const LABEL_EDGE_SPACE =
  /^[ \u00a0\u1680\u2000-\u200a\u202f\u205f\u3000\ufeff]|[ \u00a0\u1680\u2000-\u200a\u202f\u205f\u3000\ufeff]$/;
/**
 * 짝이 없는 surrogate 다. 승인 카드는 브라우저에서도 이 검사를 부르므로 ES2024 의 `isWellFormed` 와
 * 정규식 lookbehind 를 쓰지 않는다. 둘 다 Safari 16.4 이전에 없다.
 */
const LABEL_LONE_SURROGATE =
  /[\ud800-\udbff](?![\udc00-\udfff])|(?:^|[^\ud800-\udbff])[\udc00-\udfff]/;

/** 불량 표시 이름만 버리고 승인 줄과 값은 유지한다. */
export function approvalArgLabels(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const entries = Object.entries(value);
  if (entries.length > 64) return {};
  const names = new Set<string>();
  const labels: Record<string, string> = {};
  for (const [key, label] of entries) {
    if (
      !LABEL_KEY.test(key) ||
      LABEL_RESERVED.has(key) ||
      typeof label !== "string" ||
      [...label].length < 1 ||
      [...label].length > 80 ||
      LABEL_EDGE_SPACE.test(label) ||
      LABEL_FORBIDDEN.test(label) ||
      LABEL_LONE_SURROGATE.test(label) ||
      names.has(label) ||
      (Object.hasOwn(value, label) && key !== label)
    )
      return {};
    names.add(label);
    labels[key] = label;
  }
  return new TextEncoder().encode(JSON.stringify(labels)).length <= 16384
    ? labels
    : {};
}

/** 가린 응답 인자에 남아 있는 원래 키의 이름만 브라우저로 옮긴다. 전체 map을 먼저 검증한다. */
export function visibleApprovalArgLabels(
  value: unknown,
  argsJson: string | null,
): Record<string, string> {
  const checked = approvalArgLabels(value);
  const args = argsObject(argsJson);
  return args === null
    ? {}
    : Object.fromEntries(
        Object.entries(checked).filter(([key]) => Object.hasOwn(args, key)),
      );
}

/** 표시만 바꾸고 원래 키와 가림 표시는 유지한다. 충돌한 줄에는 원래 키를 병기한다. */
export function approvalArgLabel(
  key: string,
  labels?: Readonly<Record<string, string>>,
  rawKeys?: readonly string[],
): string {
  const checked = approvalArgLabels(labels);
  const nameOf = (raw: string) =>
    Object.hasOwn(checked, raw) ? checked[raw] : raw;
  if (!rawKeys) return nameOf(key);
  const keys = [...new Set(rawKeys)];
  const names = keys.map((raw) => {
    const label = nameOf(raw);
    return keys.some(
      (other) =>
        other !== raw &&
        (nameOf(other) === label || other === label || nameOf(other) === raw),
    )
      ? `${label} (${raw})`
      : label;
  });
  // 병기한 문구도 원래 키와 겹칠 수 있다. 모든 최종 이름을 예약한 뒤 겹친 줄에만 번호를 붙인다.
  const occupied = new Set(names);
  const counts = new Map<string, number>();
  for (const name of names) counts.set(name, (counts.get(name) ?? 0) + 1);
  const unique = names.map((name) => {
    if (counts.get(name) === 1) return name;
    let number = 1;
    while (occupied.has(`${name} [${number}]`)) number++;
    const final = `${name} [${number}]`;
    occupied.add(final);
    return final;
  });
  return unique[keys.indexOf(key)] ?? nameOf(key);
}

/**
 * 가려진 글이 든 맨 위 인자의 키다. 승인할 수 없는 까닭으로 어느 칸이 가려졌는지 보인다(ADR-089).
 *
 * <p>가렸는지는 Control Plane 이 정하고 화면은 그 표시만 찾는다. 키가 가려진 칸은 가려진 이름 그대로 낸다.
 */
export function hiddenArgKeys(argsJson: string | null): string[] {
  const parsed = argsObject(argsJson);
  if (parsed === null) return [];
  return Object.entries(parsed)
    .filter(
      ([key, value]) =>
        key.includes(HIDDEN_MARK) || readableValue(value).includes(HIDDEN_MARK),
    )
    .map(([key]) => key);
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
 * <p>표시 이름과 무관하게 키는 원래 이름 그대로 두고, 값이 비어 있지 않은
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

/** 서버 라우트를 부르고 오류 본문의 `code` 만 꺼낸다. 원고 저장 카드도 같이 쓴다. */
export { call as callConnectorApi };

export const readConnectorActions = (conversationId: string) =>
  call<ConnectorAction[]>(
    `/api/chat/conversations/${conversationId}/connector-actions`,
  );

/** `conversationGrant` 가 참이면 이 대화에서 계속 허용한다. `grant` 와 함께 보내지 않는다. */
export const approveConnectorAction = (
  actionId: string,
  grant: GrantPeriod | null,
  conversationGrant = false,
) =>
  call<ConnectorAction>(`/api/connector-actions/${actionId}/approve`, {
    method: "POST",
    body: conversationGrant ? { grant, conversationGrant } : { grant },
  });

export const rejectConnectorAction = (actionId: string) =>
  call<ConnectorAction>(`/api/connector-actions/${actionId}/reject`, {
    method: "POST",
  });

export const readConnectorGrants = () =>
  call<ConnectorGrant[]>("/api/connector-grants");

export const revokeConnectorGrant = (grantId: number) =>
  call<null>(`/api/connector-grants/${grantId}`, { method: "DELETE" });

export const readConversationGrants = (conversationId: string) =>
  call<ConversationGrant[]>(
    `/api/chat/conversations/${conversationId}/connector-conversation-grants`,
  );

export const revokeConversationGrant = (
  conversationId: string,
  grantId: number,
) =>
  call<null>(
    `/api/chat/conversations/${conversationId}/connector-conversation-grants/${grantId}`,
    { method: "DELETE" },
  );
