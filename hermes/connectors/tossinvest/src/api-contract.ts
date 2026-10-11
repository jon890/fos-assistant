import { TossinvestError } from "./errors.ts";

import {
  OPERATIONS,
  SCHEMAS,
  type Operation,
} from "./api-contract-generated.ts";
export * from "./api-contract-generated.ts";
export const CURRENT_OPERATIONS = new Set([
  "issueOAuth2Token",
  "getAccounts",
  "getPrices",
  "getStocks",
  "getHoldings",
  "getBuyingPower",
  "getSellableQuantity",
  "getOrders",
  "getStockInvestorTrading",
  "getStockProgramTrades",
  "getStockShortSelling",
  "getStockCreditTrades",
  "getStockSecuritiesLending",
]);
export function matchesOperation(
  operation: Operation,
  method: string,
  path: string,
): boolean {
  const template = operation.path.split("/");
  const segments = path.split("/");
  return (
    operation.method === method &&
    template.length === segments.length &&
    template.every((part, i) =>
      part.startsWith("{") ? Boolean(segments[i]) : part === segments[i],
    )
  );
}
export function operationFor(method: string, path: string): Operation {
  const operation = OPERATIONS.find((item) =>
    matchesOperation(item, method, path),
  );
  if (!operation) throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return operation;
}
const invalidResponse = (): never => {
  throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
};

/** JSON number 원문에서 정확한 정수를 만든다. 큰 지수는 자리수로 먼저 거절한다. */
export function exactInt64(source: string): bigint {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(source);
  if (!match) return invalidResponse();
  let digits = `${match[2]}${match[3] ?? ""}`.replace(/^0+/, "");
  if (!digits) return 0n;
  const exponent = (match[4] ?? "0")
    .replace(/^([+-]?)0+/, "$1")
    .replace(/^[+-]?$/, "0");
  if (exponent.replace(/^[+-]/, "").length > 7) return invalidResponse();
  const scale = Number(exponent) - (match[3]?.length ?? 0);
  if (scale >= 0) {
    if (digits.length + scale > 19) return invalidResponse();
    digits += "0".repeat(scale);
  } else {
    const keep = digits.length + scale;
    if (keep <= 0 || /[1-9]/.test(digits.slice(keep))) return invalidResponse();
    digits = digits.slice(0, keep);
    if (digits.length > 19) return invalidResponse();
  }
  const value = BigInt(`${match[1]}${digits}`);
  if (value < -9223372036854775808n || value > 9223372036854775807n)
    return invalidResponse();
  return value;
}

/** 원문 위치는 holder/key로 보관한다. 같은 이름의 비schema 필드는 변환하지 않는다. */
export function parseApiResponse(
  raw: string,
  operation: Operation,
  status: number,
): any {
  const sources = new WeakMap<object, Map<string, string>>();
  const data = JSON.parse(
    raw,
    function (
      this: object,
      key: string,
      value: unknown,
      context?: { source?: string },
    ) {
      if (typeof value === "number") {
        if (typeof context?.source !== "string") return invalidResponse();
        let values = sources.get(this);
        if (!values) {
          values = new Map();
          sources.set(this, values);
        }
        values.set(key, context.source);
      }
      return value;
    },
  );
  function validate(
    value: any,
    schema: any,
    holder: object,
    key: string,
    owner = "",
  ): any {
    if (schema.$ref) {
      const name = schema.$ref.split("/").pop();
      return validate(value, SCHEMAS[name], holder, key, name);
    }
    if (
      value === null &&
      (schema.nullable ||
        schema.type === "null" ||
        schema.type?.includes?.("null"))
    )
      return null;
    for (const union of [schema.oneOf, schema.anyOf])
      if (union) {
        for (const branch of union) {
          try {
            return validate(value, branch, holder, key, owner);
          } catch {}
        }
        return invalidResponse();
      }
    if (schema.allOf)
      for (const branch of schema.allOf)
        value = validate(value, branch, holder, key, owner);
    if (schema.format === "int64") {
      const source = sources.get(holder)?.get(key);
      if (typeof value !== "number" || !source) return invalidResponse();
      const integer = exactInt64(source);
      if (
        ((owner === "Account" && key === "accountSeq") ||
          (owner === "OAuth2TokenResponse" && key === "expires_in")) &&
        integer <= 0n
      )
        return invalidResponse();
      if (
        (schema.minimum !== undefined && integer < BigInt(schema.minimum)) ||
        (schema.maximum !== undefined && integer > BigInt(schema.maximum))
      )
        return invalidResponse();
      return (owner === "Account" && key === "accountSeq") ||
        integer > BigInt(Number.MAX_SAFE_INTEGER) ||
        integer < BigInt(Number.MIN_SAFE_INTEGER)
        ? integer.toString()
        : Number(integer);
    }
    const types = Array.isArray(schema.type)
      ? schema.type
      : schema.type
        ? [schema.type]
        : [];
    const kind =
      value === null ? "null" : Array.isArray(value) ? "array" : typeof value;
    if (
      types.length &&
      !types.includes(kind) &&
      !(
        types.includes("integer") &&
        typeof value === "number" &&
        Number.isSafeInteger(value)
      )
    )
      return invalidResponse();
    if (kind === "number" && !Number.isFinite(value)) return invalidResponse();
    if (kind === "object") {
      for (const required of schema.required ?? []) {
        // 1.2.24 공식 ETF 예시만 영문명이 없다. 다른 분류·필드와 존재하는 값은 그대로 검증한다.
        if (
          !Object.hasOwn(value, required) &&
          !(
            owner === "StockInfo" &&
            required === "englishName" &&
            value.securityType === "ETF"
          )
        )
          return invalidResponse();
      }
      for (const [property, child] of Object.entries(schema.properties ?? {})) {
        if (Object.hasOwn(value, property))
          value[property] = validate(
            value[property],
            child,
            value,
            property,
            owner,
          );
      }
    }
    if (kind === "array" && schema.items)
      value.forEach((item: any, i: number) => {
        value[i] = validate(item, schema.items, value, String(i), owner);
      });
    if (kind === "string") {
      if (
        schema.maxLength !== undefined &&
        Array.from(value).length > schema.maxLength
      )
        return invalidResponse();
      if (schema.format === "decimal" && !/^-?\d+(\.\d+)?$/.test(value))
        return invalidResponse();
      if (schema.pattern && !new RegExp(schema.pattern).test(value))
        return invalidResponse();
    }
    return value;
  }
  // 공통 인증/IP 오류는 개별 operation에 status가 생략돼도 공식 오류 schema로 읽는다.
  const schema =
    operation.responses[String(status)] ??
    (status >= 400
      ? {
          $ref: `#/components/schemas/${operation.id === "issueOAuth2Token" ? "OAuth2ErrorResponse" : "ErrorResponse"}`,
        }
      : undefined);
  if (!schema) return invalidResponse();
  return validate(data, schema, { "": data }, "");
}

/** 현재 성공 envelope에 공식 result 이외의 필드는 없다. 확장 필드를 임의 metadata로 노출하지 않는다. */
export const envelopeMetadata = (
  _data: unknown,
): Record<string, unknown> => ({});
