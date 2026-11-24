import { expect, test } from "bun:test";
import { channelToken } from "./github-token.ts";
import { safeError } from "./errors.ts";

const channel = "https://example.test/token";

test("같은 도구가 다시 호출되면 통로에서 갱신된 토큰을 받는다", async () => {
  let calls = 0;
  const current = channelToken(channel, async (url, init) => {
    expect(String(url)).toBe(channel);
    expect(init?.redirect).toBe("error");
    expect(new Headers(init?.headers).has("Authorization")).toBe(false);
    return Response.json({ access_token: ` sample-${++calls} ` });
  });
  expect(await current()).toBe("sample-1");
  expect(await current()).toBe("sample-2");
});

for (const [status, code] of [
  [401, "CAREER_GITHUB_UNAUTHORIZED"],
  [404, "CAREER_GITHUB_NOT_CONFIGURED"],
  [409, "CAREER_GITHUB_UNAUTHORIZED"],
  [503, "CAREER_GITHUB_UNAVAILABLE"],
] as const) {
  test(`통로의 ${status} 오류를 알릴 때 응답 본문과 주소를 숨긴다`, async () => {
    const current = channelToken(channel, async () => new Response("sample-private-body", { status }));
    const result = await current().catch(safeError);
    expect(result).toMatchObject({ code });
    expect(JSON.stringify(result)).not.toContain("sample-private-body");
    expect(JSON.stringify(result)).not.toContain(channel);
  });
}
