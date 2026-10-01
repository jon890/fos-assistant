import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import type { ControlPlaneResult } from "@/lib/control-plane-result";
import {
  CONNECTION_ERROR_MESSAGES,
  CONNECTOR_ID_PATTERN,
  FIELD_KEY_PATTERN,
  connectionErrorMessage,
  type ConnectorTool,
  type ToolApproval,
  type ToolRisk,
} from "@/lib/connection";
import { readJsonBody } from "@/lib/json-body";

/** Control Plane 의 오류 본문을 브라우저에 그대로 전달하지 않는다. 표에 없는 코드는 연결 실패로 바꾼다. */
export function connectionResponse<T>(result: ControlPlaneResult<T>) {
  if (!result.ok) {
    const code = Object.hasOwn(CONNECTION_ERROR_MESSAGES, result.code)
      ? result.code
      : "CONNECTOR_OPERATION_FAILED";
    return NextResponse.json(
      { code, message: connectionErrorMessage(code) },
      { status: result.status },
    );
  }
  return NextResponse.json(result.data, { status: result.status });
}

export function invalidConnectionRequest() {
  return NextResponse.json(
    {
      code: "VALIDATION_FAILED",
      message: connectionErrorMessage("VALIDATION_FAILED"),
    },
    { status: 400 },
  );
}

export function isConnectorId(value: string): boolean {
  return CONNECTOR_ID_PATTERN.test(value);
}

export function isFieldKey(value: string): boolean {
  return FIELD_KEY_PATTERN.test(value);
}

/** 요청 본문의 `values` 만 읽는다. 문자열이 아닌 값이 하나라도 있으면 거절한다. */
export async function readValuesBody(
  request: Request,
): Promise<Record<string, string> | null> {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return null;
  const values = parsed.body.values;
  if (!values || typeof values !== "object" || Array.isArray(values))
    return null;
  const entries = Object.entries(values);
  if (entries.some(([, value]) => typeof value !== "string")) return null;
  return Object.fromEntries(entries) as Record<string, string>;
}

type Kind = "catalog" | "connection" | "options" | "admin" | "adminItem";

/** 비정상 응답 본문 때문에 Control Plane 호출 자체가 던져도 고정 오류로 돌린다. */
export async function connectorCall<T>(
  path: string,
  kind: Kind,
  init: { method?: string; body?: unknown } = {},
) {
  try {
    const result = await callControlPlane<unknown>(path, init);
    if (!result.ok) return result;
    return { ...result, data: safeData(kind, result.data) as T };
  } catch {
    return {
      ok: false as const,
      status: 502,
      code: "CONNECTOR_OPERATION_FAILED",
      message: "",
    };
  }
}

function safeData(kind: Kind, value: unknown) {
  switch (kind) {
    case "catalog":
      return list(value).map(safeSummary);
    case "connection":
      return safeConnection(value);
    case "options":
      return list(value).map(safeOption);
    case "admin":
      return list(value).map(safeAdmin);
    case "adminItem":
      return safeAdmin(value);
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

function bool(value: unknown): boolean {
  if (typeof value !== "boolean") throw new Error();
  return value;
}

function connectorId(value: unknown): string {
  const id = text(value);
  if (!isConnectorId(id)) throw new Error();
  return id;
}

function status(value: unknown) {
  if (value !== "DISCONNECTED" && value !== "PENDING" && value !== "READY")
    throw new Error();
  return value;
}

function agentCode(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const code = text(value);
  if (!CONNECTOR_ID_PATTERN.test(code)) throw new Error();
  return code;
}

function stringMap(value: unknown): Record<string, string> {
  if (value === null || value === undefined) return {};
  const source = record(value);
  const result: Record<string, string> = {};
  for (const [key, entry] of Object.entries(source)) result[key] = text(entry);
  return result;
}

/** 응답 계약의 칸만 복사해 비밀값이 추가된 원격 응답도 브라우저로 옮기지 않는다. */
function safeSummary(value: unknown) {
  const item = record(value);
  return {
    id: connectorId(item.id),
    title: text(item.title),
    description: text(item.description ?? ""),
    fields: list(item.fields ?? []).map(safeField),
    tools: safeTools(item.tools),
    myStatus: status(item.myStatus),
    available: bool(item.available),
  };
}

const TOOL_RISKS = ["READ", "SENSITIVE", "WRITE", "DESTRUCTIVE", "FINANCIAL"];
const TOOL_APPROVALS = ["NONE", "REQUIRED", "ALWAYS"];

/** 도구 목록이 없거나 모양이 틀리면 빈 목록으로 두고, 값이 틀린 항목만 뺀다. */
function safeTools(value: unknown): ConnectorTool[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry): ConnectorTool[] => {
    if (!entry || typeof entry !== "object") return [];
    const item = entry as Record<string, unknown>;
    if (
      typeof item.name !== "string" ||
      typeof item.risk !== "string" ||
      !TOOL_RISKS.includes(item.risk) ||
      typeof item.approval !== "string" ||
      !TOOL_APPROVALS.includes(item.approval)
    )
      return [];
    return [
      {
        name: item.name,
        title: typeof item.title === "string" ? item.title : null,
        risk: item.risk as ToolRisk,
        approval: item.approval as ToolApproval,
      },
    ];
  });
}

function undeclaredCount(value: unknown): number {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? (value as number)
    : 0;
}

function safeField(value: unknown) {
  const item = record(value);
  const key = text(item.key);
  if (!isFieldKey(key)) throw new Error();
  return {
    key,
    label: text(item.label),
    description: nullableText(item.description),
    secret: bool(item.secret),
    required: bool(item.required),
    pattern: nullableText(item.pattern),
    hasOptions: bool(item.hasOptions),
    autoSelectSingle: bool(item.autoSelectSingle),
  };
}

function safeOption(value: unknown) {
  const item = record(value);
  return { value: text(item.value), label: text(item.label) };
}

function safeConnection(value: unknown) {
  const item = record(value);
  const checkedAt = nullableText(item.checkedAt);
  if (checkedAt !== null && !Number.isFinite(Date.parse(checkedAt)))
    throw new Error();
  const secretPrefixes = stringMap(item.secretPrefixes);
  if (Object.values(secretPrefixes).some((prefix) => prefix.length > 4))
    throw new Error();
  return {
    connectorId: connectorId(item.connectorId),
    status: status(item.status),
    secretPrefixes,
    values: stringMap(item.values),
    checkedAt,
    agentCode: agentCode(item.agentCode),
    restartRequired: bool(item.restartRequired),
    undeclaredTools: undeclaredCount(item.undeclaredTools),
  };
}

function safeAdmin(value: unknown) {
  const item = record(value);
  if (!Number.isSafeInteger(item.userId) || (item.userId as number) <= 0)
    throw new Error();
  return {
    connectorId: connectorId(item.connectorId),
    userId: item.userId as number,
    displayName: nullableText(item.displayName),
    status: status(item.status),
    agentCode: agentCode(item.agentCode),
    restartRequired: bool(item.restartRequired),
    undeclaredTools: undeclaredCount(item.undeclaredTools),
  };
}
