import manifest from "../assistant.json";

// 표시용 설명만 다룬다. 위험도와 승인, 허락은 fos-assistant 의 assistant.json 읽기 쪽이 정한다.
// 유니코드 코드 포인트와 짝 없는 서러게이트 처리까지 fos-assistant 의 schema 2 읽기와 같다.
export function normalizeToolDescription(value: unknown): string | null {
  if (
    typeof value !== "string" ||
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069\ud800-\udfff]/u.test(
      value,
    )
  )
    return null;
  const text = value.trim();
  if (!text || Array.from(text).length > 2000 || new TextEncoder().encode(text).length > 8192)
    return null;
  return text;
}

export function withToolDescriptions<T extends Record<string, { schema: unknown }>>(
  definitions: T,
  declared: { schema: number; tools: Record<string, { description?: unknown }> } = manifest,
): { [K in keyof T]: T[K] & { description: string } } {
  const names = Object.keys(definitions);
  if (
    declared.schema !== 2 ||
    JSON.stringify([...names].sort()) !== JSON.stringify(Object.keys(declared.tools).sort())
  )
    throw new Error("CAREER_TOOL_METADATA_MISMATCH");
  return Object.fromEntries(
    names.map((name) => {
      const description = normalizeToolDescription(declared.tools[name]?.description);
      if (description === null) throw new Error("CAREER_TOOL_METADATA_INVALID");
      return [name, { ...definitions[name], description }];
    }),
  ) as { [K in keyof T]: T[K] & { description: string } };
}
