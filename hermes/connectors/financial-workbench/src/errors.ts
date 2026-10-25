export class WorkbenchError extends Error {
  constructor(
    readonly code: string,
    readonly extra?: Record<string, unknown>,
  ) {
    super(code);
  }
}
const fail = (error: unknown) => {
  const value =
    error instanceof WorkbenchError
      ? { code: error.code, ...error.extra }
      : { code: "WORKBENCH_UNAVAILABLE" };
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
