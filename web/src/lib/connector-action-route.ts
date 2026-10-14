import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import type { ControlPlaneResult } from "@/lib/control-plane-result";
import { CONNECTOR_ID_PATTERN, type ToolRisk } from "@/lib/connection";
import {
  ACTION_ID_PATTERN,
  CONNECTOR_ACTION_ERROR_MESSAGES,
  CONNECTOR_ACTION_STATUSES,
  connectorActionErrorMessage,
  visibleApprovalArgLabels,
  type ConnectorAction,
  type ConnectorActionStatus,
  type ConnectorGrant,
  type ConversationGrant,
  type FinancialSummary,
} from "@/lib/connector-action";
import {
  CONTENT_DRAFT_JOB_STATUSES,
  type ContentDraftJobStatus,
  type ContentDraftSummary,
} from "@/lib/content-draft-snapshot";
import { isPublicId } from "@/lib/conversation-id";

/** 브라우저에 그대로 넘기는 오류 코드다. 그 밖의 코드는 `INTERNAL_ERROR` 로 바꾼다. */
const PASSED_CODES = new Set([
  ...Object.keys(CONNECTOR_ACTION_ERROR_MESSAGES),
  "VALIDATION_FAILED",
  "UNAUTHENTICATED",
  "FORBIDDEN",
]);

const TOOL_RISKS = ["READ", "SENSITIVE", "WRITE", "DESTRUCTIVE", "FINANCIAL"];

export function isActionId(value: string): boolean {
  return ACTION_ID_PATTERN.test(value);
}

export function isGrantId(value: string): boolean {
  return /^[1-9][0-9]{0,15}$/.test(value);
}

export function invalidConnectorActionRequest() {
  return errorResponse(
    "VALIDATION_FAILED",
    connectorActionErrorMessage("VALIDATION_FAILED"),
    400,
  );
}

/** Control Plane 의 오류 원문을 브라우저에 넘기지 않는다. 문구는 코드로 다시 정한다. */
export function connectorActionResponse<T>(result: ControlPlaneResult<T>) {
  if (!result.ok) {
    const code = PASSED_CODES.has(result.code) ? result.code : "INTERNAL_ERROR";
    return errorResponse(
      code,
      connectorActionErrorMessage(code),
      result.status,
    );
  }
  // 본문이 없는 성공(204)은 본문 없이 돌려준다.
  if (result.status === 204) return new NextResponse(null, { status: 204 });
  return NextResponse.json(result.data, { status: result.status });
}

type Kind = "actions" | "action" | "grants" | "conversationGrants" | "none";

/** 응답 모양이 계약과 달라 읽다가 던져도 고정 오류로 돌린다. */
export async function connectorActionCall<T>(
  path: string,
  kind: Kind,
  init: { method?: string; body?: unknown } = {},
): Promise<ControlPlaneResult<T>> {
  try {
    const result = await callControlPlane<unknown>(path, init);
    if (!result.ok) return result;
    return { ...result, data: safeData(kind, result.data) as T };
  } catch {
    return { ok: false, status: 502, code: "INTERNAL_ERROR", message: "" };
  }
}

function safeData(kind: Kind, value: unknown) {
  switch (kind) {
    case "actions":
      return list(value).map(safeAction);
    case "action":
      return safeAction(value);
    case "grants":
      return list(value).map(safeGrant);
    case "conversationGrants":
      return list(value).map(safeConversationGrant);
    case "none":
      return null;
  }
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error();
  return value;
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error();
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  if (typeof value !== "string") throw new Error();
  return value;
}

function nullableText(value: unknown): string | null {
  return value === null || value === undefined ? null : text(value);
}

function connectorId(value: unknown): string {
  const id = text(value);
  if (!CONNECTOR_ID_PATTERN.test(id)) throw new Error();
  return id;
}

/** 응답 계약의 칸만 복사한다. 계약에 없는 칸이 더해져도 브라우저로 옮기지 않는다. */
function safeAction(value: unknown): ConnectorAction {
  const item = record(value);
  const actionId = text(item.actionId);
  if (!isActionId(actionId)) throw new Error();
  const status = text(item.status) as ConnectorActionStatus;
  if (!CONNECTOR_ACTION_STATUSES.includes(status)) throw new Error();
  return {
    actionId,
    connectorId: connectorId(item.connectorId),
    toolName: nullableText(item.toolName),
    title: text(item.title),
    risk:
      typeof item.risk === "string" && TOOL_RISKS.includes(item.risk)
        ? (item.risk as ToolRisk)
        : null,
    status,
    argsJson: nullableText(item.argsJson),
    // 실행 결과의 본문은 화면이 그리지 않는다. 외부 서비스의 글이라 브라우저로 옮기지 않는다.
    resultText: null,
    errorCode: nullableText(item.errorCode),
    createdAt: text(item.createdAt),
    expiresAt: nullableText(item.expiresAt),
    grantAllowed: item.grantAllowed === true,
    hiddenArgs: item.hiddenArgs === true,
    argLabels: visibleApprovalArgLabels(
      item.argLabels,
      nullableText(item.argsJson),
    ),
    conversationGrantAllowed: item.conversationGrantAllowed === true,
    contentDraft:
      item.contentDraft == null ? null : safeContentDraft(item.contentDraft),
    financial: item.financial == null ? null : safeFinancial(item.financial),
  };
}

const ACCOUNT_SEQ_PATTERN = /^[1-9][0-9]{0,18}$/;
const CURRENCY_PATTERN = /^[A-Z]{3}$/;
const DECIMAL_PATTERN = /^[0-9]+(\.[0-9]+)?$/;

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  const item = text(value);
  if (!allowed.includes(item as T)) throw new Error();
  return item as T;
}

function matching(value: unknown, pattern: RegExp): string {
  const item = text(value);
  if (!pattern.test(item)) throw new Error();
  return item;
}

/** 수량, 금액, 가격이다. 없으면 null 이고 있으면 30자 이하의 십진 글이다. */
function nullableDecimal(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const item = matching(value, DECIMAL_PATTERN);
  if (item.length > 30) throw new Error();
  return item;
}

/**
 * 금융 요약은 계약의 칸만 옮긴다. 저장한 내용을 열지 못한 줄은 나머지 칸을 모두 비운다. 열었다면 계좌 순번과
 * 방향 같은 칸의 모양을 검사하고, 틀리면 던져 고정 오류로 바꾼다.
 */
function safeFinancial(value: unknown): FinancialSummary {
  const item = record(value);
  if (item.available !== true)
    return {
      available: false,
      accountSeq: null,
      operation: null,
      symbol: null,
      market: null,
      currency: null,
      side: null,
      quantity: null,
      orderAmount: null,
      price: null,
      orderType: null,
      timeInForce: null,
      orderId: null,
      normalization: [],
    };
  return {
    available: true,
    accountSeq: matching(item.accountSeq, ACCOUNT_SEQ_PATTERN),
    operation: oneOf(item.operation, ["CREATE", "CANCEL"] as const),
    symbol: nullableText(item.symbol),
    market: nullableText(item.market),
    currency: matching(item.currency, CURRENCY_PATTERN),
    side: oneOf(item.side, ["BUY", "SELL"] as const),
    quantity: nullableDecimal(item.quantity),
    orderAmount: nullableDecimal(item.orderAmount),
    price: nullableDecimal(item.price),
    orderType: oneOf(item.orderType, ["LIMIT", "MARKET"] as const),
    timeInForce: nullableText(item.timeInForce),
    orderId: nullableText(item.orderId),
    normalization: list(item.normalization).map((change) => {
      const entry = record(change);
      return {
        field: text(entry.field),
        before: nullableText(entry.before),
        after: text(entry.after),
      };
    }),
  };
}

function publicId(value: unknown): string {
  const id = text(value);
  if (!isPublicId(id)) throw new Error();
  return id;
}

/** 원고 저장 요약은 계약의 칸만 옮긴다. 승인자와 바인딩 판 같은 칸이 더해져도 브라우저로 옮기지 않는다. */
function safeContentDraft(value: unknown): ContentDraftSummary {
  const item = record(value);
  const status = text(item.status) as ContentDraftJobStatus;
  if (!CONTENT_DRAFT_JOB_STATUSES.includes(status)) throw new Error();
  if (!Number.isSafeInteger(item.revision)) throw new Error();
  return {
    snapshotId: publicId(item.snapshotId),
    draftId: publicId(item.draftId),
    revision: item.revision as number,
    conversationId: publicId(item.conversationId),
    status,
    stage: nullableText(item.stage),
    saveClicked: item.saveClicked === true,
    errorCode: nullableText(item.errorCode),
  };
}

function grantId(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) throw new Error();
  return value as number;
}

/**
 * 대화 허락은 이름과 끝나는 시각, 다른 에이전트에 준 허락이면 그 에이전트 이름만 옮긴다. 커넥터와 도구의 원래 이름은 화면이
 * 쓰지 않는다.
 */
function safeConversationGrant(value: unknown): ConversationGrant {
  const item = record(value);
  return {
    grantId: grantId(item.grantId),
    title: text(item.title),
    expiresAt: text(item.expiresAt),
    agentName: typeof item.agentName === "string" ? item.agentName : null,
  };
}

function safeGrant(value: unknown): ConnectorGrant {
  const item = record(value);
  grantId(item.grantId);
  return {
    grantId: item.grantId as number,
    connectorId: connectorId(item.connectorId),
    toolName: text(item.toolName),
    title: typeof item.title === "string" ? item.title : null,
    expiresAt: text(item.expiresAt),
  };
}
