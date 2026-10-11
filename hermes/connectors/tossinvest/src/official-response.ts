import { SCHEMAS } from "./api-contract.ts";

const unsupported = (): never => {
  throw new Error("TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA");
};

/** allOf의 알려진 필드를 먼저 합쳐 설명 전용 result를 구체화한다. */
function objectProperties(schema: any): Record<string, any> {
  if (!schema || schema.oneOf || schema.anyOf || schema.additionalProperties)
    return unsupported();
  if (schema.$ref)
    return objectProperties(SCHEMAS[schema.$ref.split("/").pop()]);
  if (!schema.properties && !schema.allOf) return unsupported();
  const properties: Record<string, any> = { ...schema.properties };
  for (const branch of schema.allOf ?? []) {
    for (const [key, child] of Object.entries(objectProperties(branch))) {
      // 설명 전용 placeholder는 구체 정의와 합친 뒤에만 투영한다.
      properties[key] = { ...properties[key], ...(child as any) };
    }
  }
  return properties;
}

/** 검증된 응답에서 공식 필드만 남긴다. null·부재·문자열과 배열 순서는 보존한다. */
export function officialFields(value: any, schema: any): any {
  if (value === null) return null;
  if (!schema || schema.anyOf || schema.additionalProperties)
    return unsupported();
  if (schema.oneOf) {
    const branches = schema.oneOf;
    // 공식 통계의 ref와 null 두 분기만 지원한다. 일반 union을 추론하지 않는다.
    const reference = branches.find((branch: any) => branch.$ref);
    const nullable = branches.find((branch: any) => branch.type === "null");
    if (
      branches.length !== 2 ||
      !reference ||
      !nullable ||
      Object.keys(reference).length !== 1 ||
      Object.keys(nullable).length !== 1
    )
      return unsupported();
    return officialFields(value, reference);
  }
  if (schema.$ref)
    return officialFields(value, SCHEMAS[schema.$ref.split("/").pop()]);
  if (schema.allOf)
    return officialFields(value, { properties: objectProperties(schema) });
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
