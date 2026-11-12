import { operationFor, type Operation } from "./api-contract.ts";
import { TossinvestError } from "./errors.ts";
import type { Exchange } from "./transport.ts";
import { reject, UNKNOWN } from "./trading-body.ts";

const IDS = [
  "createConditionalOrder",
  "modifyConditionalOrder",
  "cancelConditionalOrder",
] as const;
const ALL = IDS;
const WRITES = ["createConditionalOrder", "modifyConditionalOrder"] as const;
const TARGETED = ["modifyConditionalOrder", "cancelConditionalOrder"] as const;

/**
 * 1.2.24 operation 응답과 공식 개요의 HTTP/code 표를 함께 대조한 명백한 거절이다.
 * 같은 status 라도 이 표에 없는 code 와 불량 envelope 는 UNKNOWN 이다. 422 전체를 FAILED 로 보지 않는다.
 */
const REJECTIONS: Array<[readonly string[], number, string[]]> = [
  [ALL, 400, ["invalid-request", "account-header-required"]],
  [WRITES, 400, ["confirm-high-value-required"]],
  [["createConditionalOrder"], 404, ["stock-not-found"]],
  [TARGETED, 404, ["conditional-order-not-found"]],
  [ALL, 404, ["account-not-found", "edge-blocked"]],
  [
    ALL,
    401,
    [
      "invalid-token",
      "edge-blocked",
      "expired-token",
      "token-revoked",
      "login-user-not-found",
    ],
  ],
  [ALL, 403, ["edge-blocked", "ip-not-allowed", "forbidden"]],
  [ALL, 414, ["edge-blocked"]],
  [WRITES, 415, ["unsupported-content-type"]],
  [WRITES, 422, ["duplicate-conditional-order", "condition-already-met"]],
  [["createConditionalOrder"], 422, ["idempotency-key-conflict"]],
];

/** 조건주문 쓰기 세 경로인지 본다. 나머지 금융 경로는 일반 주문 분기가 맡는다. */
export function isConditionalWrite(method: string, path: string): boolean {
  try {
    return (IDS as readonly string[]).includes(operationFor(method, path).id);
  } catch {
    return false;
  }
}

export function conditionalOperation(method: string, path: string): Operation {
  const operation = operationFor(method, path);
  if (!(IDS as readonly string[]).includes(operation.id)) reject();
  return operation;
}

/** 공식 ErrorResponse.error 의 requestId/message/code 문자열과 선택 data 의 object/null 계약이다. */
function errorEnvelope(data: any): string | null {
  const error = data?.error;
  if (
    data === null ||
    typeof data !== "object" ||
    Array.isArray(data) ||
    typeof error?.requestId !== "string" ||
    typeof error?.message !== "string" ||
    typeof error?.code !== "string" ||
    !(
      error.data === undefined ||
      error.data === null ||
      (typeof error.data === "object" && !Array.isArray(error.data))
    )
  )
    return null;
  return error.code;
}

export function conditionalRejected(
  operationId: string,
  status: number,
  data: unknown,
): boolean {
  const code = errorEnvelope(data);
  return (
    code !== null &&
    REJECTIONS.some(
      ([ids, expected, codes]) =>
        ids.includes(operationId) &&
        expected === status &&
        codes.includes(code),
    )
  );
}

/**
 * 전송 뒤 응답을 FAILED 와 UNKNOWN, 성공으로 가른다.
 * 취소는 본문 없는 204 만 성공이고, 생성·정정은 200 과 result.conditionalOrderId 가 있어야 한다.
 */
export function conditionalResult(
  operationId: string,
  response: Exchange,
): any {
  if (!response.ok)
    throw new TossinvestError(
      conditionalRejected(operationId, response.status, response.data)
        ? "TOSSINVEST_INVALID_INPUT"
        : UNKNOWN,
    );
  if (operationId === "cancelConditionalOrder") {
    if (response.status === 204 && response.empty) return {};
    throw new TossinvestError(UNKNOWN);
  }
  const id = response.data?.result?.conditionalOrderId;
  if (
    response.status !== 200 ||
    typeof id !== "string" ||
    !id ||
    id.length > 256
  )
    throw new TossinvestError(UNKNOWN);
  return response.data;
}
