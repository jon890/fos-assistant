import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

/** 공개 원문 fixture에서 계약 자료만 재생성한다. 파싱·등록 구현은 생성 범위 밖이다. */
const fixture = readFileSync(new URL("../tests/fixtures/openapi-1.2.24.json", import.meta.url));
const canonical = JSON.parse(fixture.toString());
const sourceFile = new URL("../src/api-contract.ts", import.meta.url);
const before = readFileSync(sourceFile, "utf8");
const keys = new Set(["$ref", "type", "format", "properties", "required", "items", "allOf", "oneOf", "anyOf", "nullable", "minimum", "maximum", "maxLength", "pattern", "enum", "default"]);
function descriptor(value: any): any {
  if (Array.isArray(value)) return value.map(descriptor);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).filter(([key]) => keys.has(key)).map(([key, child]) => [key, key === "properties" ? Object.fromEntries(Object.entries(child as object).map(([name, schema]) => [name, descriptor(schema)])) : descriptor(child)]));
}
function typeOf(schema: any): string {
  if (schema.$ref) return schema.$ref.split("/").pop();
  for (const [key, separator] of [["allOf", " & "], ["oneOf", " | "], ["anyOf", " | "]]) {
    if (schema[key!]) return `(${schema[key!].map(typeOf).join(separator)})${schema.nullable ? " | null" : ""}`;
  }
  if (Array.isArray(schema.type)) return schema.type.map((type: string) => typeOf({ ...schema, type })).join(" | ");
  if (schema.type === "object" || schema.properties) return `{ ${Object.entries(schema.properties ?? {}).map(([name, child]) => `${JSON.stringify(name)}${schema.required?.includes(name) ? "" : "?"}: ${typeOf(child)}`).join("; ")} }`;
  if (schema.type === "array") return `(${typeOf(schema.items)})[]`;
  if (schema.type === "integer") return schema.format === "int64" ? "number | string" : "number";
  return ({ string: "string", number: "number", boolean: "boolean", null: "null" } as Record<string, string>)[schema.type] ?? "unknown";
}
const lines = [
  "// CONTRACT-GENERATED:START",
  "// 공식 canonical JSON 1.2.24에서 추출한 계약이다. 등록 여부와 계약 존재를 구분한다.",
  'export const CONTRACT_SOURCE = "https://openapi.tossinvest.com/openapi-docs/latest/openapi.json";',
  `export const CONTRACT_SHA256 = ${JSON.stringify(createHash("sha256").update(fixture).digest("hex"))};`,
  "export interface Operation { id: string; method: string; path: string; group: string; input: any; responses: Record<string, any> }",
  "export const OPERATIONS: readonly Operation[] = [",
];
for (const [path, methods] of Object.entries(canonical.paths)) {
  for (const [method, operation] of Object.entries(methods as Record<string, any>)) {
    if (!operation.operationId) continue;
    const parameters = (operation.parameters ?? []).map((parameter: any) => {
      const resolved = parameter.$ref ? canonical.components.parameters[parameter.$ref.split("/").pop()] : parameter;
      return { name: resolved.name, in: resolved.in, required: resolved.required ?? false, schema: descriptor(resolved.schema) };
    });
    const body = Object.fromEntries(Object.entries(operation.requestBody?.content ?? {}).map(([contentType, content]: any) => [contentType, descriptor(content.schema)]));
    const responses = Object.fromEntries(Object.entries(operation.responses).map(([status, response]: any) => [status, descriptor(response.content?.["application/json"]?.schema ?? {})]));
    lines.push(`  ${JSON.stringify({ id: operation.operationId, method: method.toUpperCase(), path, group: /Rate Limits Group[^`]*`([^`]+)/.exec(operation.description)![1], input: { parameters, body }, responses })},`);
  }
}
lines.push("];", "export const SCHEMAS: Record<string, any> = {");
for (const [name, schema] of Object.entries(canonical.components.schemas)) lines.push(`  ${JSON.stringify(name)}: ${JSON.stringify(descriptor(schema))},`);
lines.push("};", "");
for (const [name, schema] of Object.entries(canonical.components.schemas)) {
  // Account의 MCP 출력 descriptor는 순번을 항상 문자열로 바꾼다.
  const output = name === "Account" ? { ...schema as any, properties: { ...(schema as any).properties, accountSeq: { type: "string" } } } : schema;
  lines.push(`export type ${name} = ${typeOf(output)};`);
}
lines.push("// CONTRACT-GENERATED:END");
const generated = lines.join("\n");
const after = before.replace(/\/\/ CONTRACT-GENERATED:START[\s\S]*?\/\/ CONTRACT-GENERATED:END/, generated);
if (process.argv.includes("--check")) {
  if (before !== after) throw new Error("TOSSINVEST_CONTRACT_OUTDATED");
} else writeFileSync(sourceFile, after);
