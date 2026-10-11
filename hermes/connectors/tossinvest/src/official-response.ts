import { SCHEMAS } from "./api-contract.ts";

/** 검증된 응답에서 공식 필드만 남긴다. null·부재·문자열과 배열 순서는 보존한다. */
export function officialFields(value: any, schema: any): any {
  if (value === null) return null;
  if (!schema || schema.oneOf || schema.anyOf)
    throw new Error("TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA");
  if (schema.$ref)
    return officialFields(value, SCHEMAS[schema.$ref.split("/").pop()]);
  if (schema.allOf)
    return schema.allOf.reduce(
      (result: any, branch: any) => ({
        ...result,
        ...officialFields(value, branch),
      }),
      {},
    );
  if (Array.isArray(value))
    return value.map((entry) => officialFields(entry, schema.items));
  if (schema.properties)
    return Object.fromEntries(
      Object.entries(schema.properties)
        .filter(([key]) => Object.hasOwn(value, key))
        .map(([key, child]) => [key, officialFields(value[key], child)]),
    );
  // 현재 공식 계약에서 쓰지 않는 객체 구조는 원문 대신 전체 오류로 막는다.
  if (typeof value === "object")
    throw new Error("TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA");
  return value;
}
