/** 상태 JSON 에서 값을 꺼내는 도구들. 화면이 준 값은 믿지 않고 모양과 길이를 다시 본다. */

export type Json = unknown;

/** 키 하나나 경로 하나. */
type Key = string | string[];

function at(value: Json, key: Key): Json {
  const keys = Array.isArray(key) ? key : [key];
  let current = value;
  for (const part of keys) {
    if (current === null || typeof current !== "object" || Array.isArray(current)) return undefined;
    current = (current as Record<string, Json>)[part];
  }
  return current;
}

/** 후보를 차례로 보고 처음 있는 값. */
export function pick(value: Json, keys: Key[]): Json {
  for (const key of keys) {
    const found = at(value, key);
    if (found !== undefined && found !== null && found !== "") return found;
  }
  return undefined;
}

/** 0 이상의 정수 원화. 숫자 글(`"12,900"`)도 받는다. 아니면 `null`. */
export function won(value: Json): number | null {
  const number =
    typeof value === "number" ? value : typeof value === "string" ? Number(value.replace(/[,원\s]/g, "")) : NaN;
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

/** 제어 문자를 뺀 한 줄 글을 `max` 자로 자른다. 비면 `null`. */
export function text(value: Json, max: number): string | null {
  if (typeof value !== "string") return null;
  const cleaned = value.replace(/[\u0000-\u001f\u007f-\u009f]/g, " ").replace(/\s+/g, " ").trim();
  return cleaned ? [...cleaned].slice(0, max).join("") : null;
}

/** `true`, `"Y"`, `"true"` 를 참으로 읽는다. */
export function yes(value: Json) {
  return value === true || value === "Y" || value === "true";
}

export function list(value: Json): Json[] {
  return Array.isArray(value) ? value : [];
}

/** 상태 JSON 글을 읽는다. 읽지 못하면 `undefined`. */
export function parseJson(raw: unknown): Json {
  if (typeof raw !== "string") return undefined;
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}
