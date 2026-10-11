/** 도구 설명만 정제한다. 도구 정책과 원문 manifest의 보호 SHA는 바꾸지 않는다. */
export function toolDescription(value: unknown): string | null {
  if (typeof value !== "string") return null;
  for (const character of value) {
    const point = character.codePointAt(0)!;
    if ((point < 32 && point !== 9 && point !== 10 && point !== 13) ||
      (point >= 0x7f && point <= 0x9f) || point === 0x061c || point === 0x200e || point === 0x200f ||
      (point >= 0x202a && point <= 0x202e) || (point >= 0x2066 && point <= 0x2069) ||
      (point >= 0xd800 && point <= 0xdfff)) return null;
  }
  const trimmed = value.trim();
  const length = Array.from(trimmed).length;
  return length >= 1 && length <= 2000 && new TextEncoder().encode(trimmed).length <= 8192 ? trimmed : null;
}
