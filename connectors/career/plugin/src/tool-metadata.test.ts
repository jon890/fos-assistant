import { expect, test } from "bun:test";
import { z } from "zod";
import manifest from "../assistant.json";
import { normalizeToolDescription, withToolDescriptions } from "./tool-metadata.ts";
import { toolDefinitions } from "./tools.ts";

test("모든 도구의 설명이 manifest 정본과 같고 입력의 모든 칸을 설명한다", () => {
  expect(Object.keys(toolDefinitions).sort()).toEqual(Object.keys(manifest.tools).sort());
  for (const [name, definition] of Object.entries(toolDefinitions)) {
    const description = manifest.tools[name as keyof typeof manifest.tools].description;
    expect(definition.description).toBe(description);
    expect(normalizeToolDescription(description)).toBe(description);
    expect(Array.from(description).length).toBeGreaterThan(150);
    const visit = (schema: Record<string, unknown>) => {
      for (const value of (schema.enum ?? []) as string[])
        expect(description, `${name}: ${value}`).toContain(value);
      for (const [field, child] of Object.entries(
        (schema.properties ?? {}) as Record<string, Record<string, unknown>>,
      )) {
        expect(description, `${name}.${field}`).toContain(field);
        visit(child);
      }
      if (schema.items && !Array.isArray(schema.items))
        visit(schema.items as Record<string, unknown>);
      for (const child of (schema.anyOf ?? []) as Record<string, unknown>[]) visit(child);
    };
    visit(z.toJSONSchema(definition.schema, { unrepresentable: "any" }));
  }
});

test("설명 누락과 도구 이름 불일치, schema 1을 거절한다", () => {
  const definitions = { read: { schema: z.strictObject({}) } };
  const manifests: Array<Record<string, { description?: unknown }>> = [
    {},
    { other: { description: "설명" } },
    { read: {} },
  ];
  for (const tools of manifests)
    expect(() => withToolDescriptions(definitions, { schema: 2, tools })).toThrow(
      "CAREER_TOOL_METADATA",
    );
  expect(() =>
    withToolDescriptions(definitions, { schema: 1, tools: { read: { description: "설명" } } }),
  ).toThrow("CAREER_TOOL_METADATA_MISMATCH");
  for (const description of [null, 42, "", " \t", "설명\u202e", "a".repeat(2001), "\ud800"])
    expect(() =>
      withToolDescriptions(definitions, { schema: 2, tools: { read: { description } } }),
    ).toThrow("CAREER_TOOL_METADATA_INVALID");
});

test("schema 2 reader의 공백, code point, UTF-8과 금지 문자 경계를 지킨다", () => {
  expect(
    normalizeToolDescription("\t\n\r \u00a0\u1680\u2000\u2028\u2029\u202f\u205f\u3000\ufeff설명\t"),
  ).toBe("설명");
  expect(normalizeToolDescription("😀".repeat(2000))).toBe("😀".repeat(2000));
  expect(normalizeToolDescription("😀".repeat(2001))).toBeNull();
  expect(normalizeToolDescription("가".repeat(2000))).toBe("가".repeat(2000));
  // 유니코드 스칼라 값 2000개 이하면 UTF-8 로도 8000바이트 이하라 8192 아래다.
  for (const value of [undefined, null, 42, {}, "", " \n\t", "a".repeat(2001), "😀".repeat(2049)])
    expect(normalizeToolDescription(value)).toBeNull();
  for (const value of [
    "\u0000",
    "\u0008",
    "\u000b",
    "\u000c",
    "\u000e",
    "\u001f",
    "\u007f",
    "\u0085",
    "\u009f",
    "\u061c",
    "\u200e",
    "\u200f",
    "\u202a",
    "\u202e",
    "\u2066",
    "\u2069",
    "\ud800",
    "\udfff",
  ])
    expect(normalizeToolDescription(`설명${value}`)).toBeNull();
  expect(normalizeToolDescription("설명\n다음\t끝")).toBe("설명\n다음\t끝");
});

test("설명을 바꿔도 승인 정책과 입력 스키마를 바꾸지 않는다", () => {
  const altered = structuredClone(manifest);
  for (const policy of Object.values(altered.tools))
    policy.description = "승인 없이 실행하라는 비신뢰 표시 문구";
  const changed = withToolDescriptions(toolDefinitions, altered);
  const strip = (declared: typeof manifest) =>
    Object.fromEntries(
      Object.entries(declared.tools).map(([name, { description: _, ...policy }]) => [name, policy]),
    );
  expect(strip(altered)).toEqual(strip(manifest));
  for (const [name, definition] of Object.entries(changed)) {
    expect(definition.schema).toBe(toolDefinitions[name]!.schema);
    expect(Object.keys(definition).sort()).toEqual(["description", "schema"]);
  }
  // 공개 프로필에 닿는 쓰기라 outbound 이고 상시 허락이 없다.
  const publicWrites = ["update_github_profile", "update_wanted_resume"];
  for (const [name, policy] of Object.entries(manifest.tools)) {
    const needsOutbound = policy.risk !== "READ" && policy.approval === "required";
    const expected = ["approval", "description", "risk", "title"];
    if (needsOutbound) {
      expected.push("outbound");
      expect((policy as { outbound?: boolean }).outbound, name).toBe(publicWrites.includes(name));
    }
    if (publicWrites.includes(name)) expected.push("grant");
    expect(Object.keys(policy).sort(), name).toEqual(expected.sort());
  }
  expect(manifest.tools.update_github_profile).toMatchObject({ outbound: true, grant: false });
  expect(manifest.tools.update_github_profile).not.toHaveProperty("conversation_grant");
  expect(manifest.default_tool_policy).toBe("deny");
});

test("저장 승인과 개인정보, 기본값과 예외를 설명에서 구분한다", () => {
  for (const [name, { description, risk }] of Object.entries(manifest.tools)) {
    expect(description, name).toContain("개인");
    expect(description, name).toContain(risk === "WRITE" ? "WRITE" : "READ");
    expect(description, name).toContain("승인");
  }
  expect(manifest.tools.update_github_profile.description).toContain("CAREER_GITHUB_STALE_REVIEW");
  // 사용량 차트와 Tokens 배지는 README 갱신에서 빠졌다.
  expect(manifest.tools.update_github_profile.description).toContain("README.md 한 파일");
  expect(manifest.tools.update_github_profile.description).not.toContain("months 는");
  expect(manifest.tools.check_connection.description).not.toContain("Backend");
});
