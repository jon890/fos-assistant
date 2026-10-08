import { VALUE_MAX_CHARS } from "./constants.ts";

export const truncateCodePoints = (value: string, limit: number) =>
  Array.from(value).slice(0, limit).join("");

/**
 * 서비스가 준 값 하나를 결과에 담는다. 외부에서 온 글이므로 길이를 자른다.
 * 금액, 수량, 비율은 API 가 준 10진수 글 그대로 둔다. 숫자로 바꾸지 않는다.
 * 글과 유한한 숫자만 통과시키고 객체, 배열, null 같은 그 밖의 꼴은 null 이다.
 */
export const serviceValue = (raw: unknown): string | number | null => {
  if (typeof raw === "string") return truncateCodePoints(raw, VALUE_MAX_CHARS);
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  return null;
};

const DECIMAL = /^-?\d+(\.\d+)?$/;

/**
 * 가격, 금액, 수량, 비율, 수수료, 세금처럼 10진수 글로 오는 값이다. 자르면 값이 바뀌므로 자르지 않는다.
 * 10진수 꼴이고 상한 안인 글과 유한한 숫자만 그대로 두고, 그 밖(상한 초과 포함)은 null 이다.
 */
export const decimalValue = (raw: unknown): string | number | null => {
  if (typeof raw === "string")
    return raw.length <= VALUE_MAX_CHARS && DECIMAL.test(raw) ? raw : null;
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  return null;
};
