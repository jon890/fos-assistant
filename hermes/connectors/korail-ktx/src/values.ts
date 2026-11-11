/** 화면에서 꺼낸 값을 다루는 도구들. 화면이 준 값은 믿지 않고 모양과 길이를 다시 본다. */

export type Json = unknown;

/** 0 이상의 정수 원화. 숫자 글(`"12,900"`)도 받는다. 아니면 `null`. */
export function won(value: Json): number | null {
  const number =
    typeof value === "number"
      ? value
      : typeof value === "string"
        ? Number(value.replace(/[,원\s]/g, ""))
        : NaN;
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

/** 제어 문자를 뺀 한 줄 글을 `max` 자로 자른다. 비면 `null`. */
export function text(value: Json, max: number): string | null {
  if (typeof value !== "string") return null;
  const cleaned = value
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return cleaned ? [...cleaned].slice(0, max).join("") : null;
}

export function list(value: Json): Json[] {
  return Array.isArray(value) ? value : [];
}

/** 꺼낸 JSON 글을 읽는다. 읽지 못하면 `undefined`. */
export function parseJson(raw: unknown): Json {
  if (typeof raw !== "string") return undefined;
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}
