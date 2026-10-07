import { ID, BLOCKED } from "./constants.ts";

export class GmailError extends Error {
  constructor(
    readonly code: string,
    readonly extra?: Record<string, unknown>,
  ) {
    super(code);
  }
}
const fail = (error: unknown) => {
  const value =
    error instanceof GmailError
      ? { code: error.code, ...error.extra }
      : { code: "GMAIL_UNAVAILABLE" };
  return {
    content: [
      { type: "text" as const, text: JSON.stringify({ error: value }) },
    ],
    isError: true,
  };
};
const ok = (value: unknown) => ({
  content: [{ type: "text" as const, text: JSON.stringify(value) }],
});
export const guard = async (work: () => Promise<unknown>) => {
  try {
    return ok(await work());
  } catch (error) {
    return fail(error);
  }
};
export const id = (value: string) => {
  if (!ID.test(value)) throw new GmailError("GMAIL_INVALID_INPUT");
  return value;
};
export const strings = (value: string) =>
  value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
export const blocked = (labels: string[]) =>
  labels.some((label) => BLOCKED.has(label.toUpperCase()));
export const codePoints = (value: string) => Array.from(value).length;
export const truncateCodePoints = (value: string, limit: number) =>
  Array.from(value).slice(0, limit).join("");
