export class TossinvestError extends Error {
  constructor(readonly code: string) {
    super(code);
  }
}
/** 모르는 예외는 서비스의 글을 싣지 않도록 코드 하나로만 돌려준다. */
const fail = (error: unknown) => {
  const code =
    error instanceof TossinvestError ? error.code : "TOSSINVEST_UNAVAILABLE";
  return {
    content: [
      { type: "text" as const, text: JSON.stringify({ error: { code } }) },
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
