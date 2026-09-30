import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import type { ControlPlaneResult } from "@/lib/control-plane-result";

const MESSAGES: Record<string, string> = {
  ACCOUNTBOOK_TOKEN_REJECTED: "가계부 토큰을 확인하지 못했어요. 토큰을 다시 확인해 주세요.",
  ACCOUNTBOOK_FAMILY_FORBIDDEN: "선택한 가족에 접근할 수 없어요. 가족 식별자를 다시 확인해 주세요.",
  ACCOUNTBOOK_UNAVAILABLE: "가계부에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  CONNECTOR_OPERATION_FAILED: "연결 설정을 적용하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  FORBIDDEN: "이 작업을 관리할 수 없어요.",
  UNAUTHENTICATED: "로그인이 필요해요.",
  VALIDATION_FAILED: "입력 내용을 다시 확인해 주세요.",
};

function message(code: string): string {
  return MESSAGES[code] ?? "요청을 처리하지 못했어요.";
}

/** Control Plane 의 오류 본문을 브라우저에 그대로 전달하지 않는다. */
export function connectionResponse<T>(result: ControlPlaneResult<T>) {
  if (!result.ok) {
    const code = Object.hasOwn(MESSAGES, result.code) ? result.code : "CONNECTOR_OPERATION_FAILED";
    return NextResponse.json({ code, message: message(code) }, { status: result.status });
  }
  return NextResponse.json(result.data, { status: result.status });
}

export async function readConnectionBody(request: Request): Promise<{ token: string; familyUuid?: string } | null> {
  try {
    const body = (await request.json()) as { token?: unknown; familyUuid?: unknown };
    if (typeof body.token !== "string" || body.token.trim().length === 0) return null;
    if (body.familyUuid !== undefined && typeof body.familyUuid !== "string") return null;
    const familyUuid = body.familyUuid?.trim();
    return { token: body.token, ...(familyUuid ? { familyUuid } : {}) };
  } catch {
    return null;
  }
}

export function invalidConnectionRequest() {
  return NextResponse.json({ code: "VALIDATION_FAILED", message: message("VALIDATION_FAILED") }, { status: 400 });
}

/** 비정상 응답 본문 때문에 Control Plane 호출 자체가 던져도 고정 오류로 돌린다. */
export async function connectorCall<T>(path: string, init: { method?: string; body?: unknown } = {}) {
  try {
    const result = await callControlPlane<unknown>(path, init);
    if (!result.ok) return result;
    const admin = path.startsWith("/api/v1/admin/");
    const data = path.endsWith("/families") ? safeFamilies(result.data)
      : Array.isArray(result.data) && admin ? result.data.map((item) => safeConnection(item, true)) : safeConnection(result.data, admin);
    return { ...result, data: data as T };
  } catch {
    return { ok: false as const, status: 502, code: "CONNECTOR_OPERATION_FAILED", message: "" };
  }
}

function safeFamilies(value: unknown) {
  if (!Array.isArray(value)) throw new Error();
  return value.map((entry: unknown) => {
    if (!entry || typeof entry !== "object") throw new Error();
    const item = entry as Record<string, unknown>;
    if (typeof item.uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.uuid)
      || typeof item.name !== "string" || !item.name.trim()) throw new Error();
    return { uuid: item.uuid, name: item.name };
  });
}

/** 응답 계약의 칸만 복사해 비밀값이 추가된 원격 응답도 브라우저로 옮기지 않는다. */
function safeConnection(value: unknown, admin: boolean) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
  const item = value as Record<string, unknown>;
  if (!["DISCONNECTED", "PENDING", "READY"].includes(String(item.status)) || typeof item.restartRequired !== "boolean") throw new Error();
  if (item.agentCode !== null && (typeof item.agentCode !== "string" || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(item.agentCode))) throw new Error();
  const common = { status: item.status, restartRequired: item.restartRequired, agentCode: item.agentCode };
  if (admin) {
    if (!Number.isSafeInteger(item.userId) || (item.userId as number) <= 0 || (item.displayName !== null && typeof item.displayName !== "string")) throw new Error();
    return { ...common, userId: item.userId, displayName: item.displayName };
  }
  if (item.tokenPrefix !== null && (typeof item.tokenPrefix !== "string" || item.tokenPrefix.length !== 8)) throw new Error();
  if (item.familyUuid !== null && (typeof item.familyUuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.familyUuid))) throw new Error();
  if (item.checkedAt !== null && (typeof item.checkedAt !== "string" || !Number.isFinite(Date.parse(item.checkedAt)))) throw new Error();
  return { ...common, tokenPrefix: item.tokenPrefix, familyUuid: item.familyUuid, checkedAt: item.checkedAt };
}
