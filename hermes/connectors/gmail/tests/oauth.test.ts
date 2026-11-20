import { expect, test } from "bun:test";
import { join } from "node:path";
import {
  authorizationUrl,
  codeChallenge,
  exchangeCode,
} from "../scripts/get_refresh_token.ts";

const probe = join(import.meta.dir, "../scripts/oauth-test-driver.ts");
async function probeMode(mode: string) {
  const child = Bun.spawn([process.execPath, probe, mode], {
    stdout: "pipe",
    stderr: "pipe",
  });
  const [exitCode, stdout, stderr] = await Promise.all([
    child.exited,
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
  ]);
  return { exitCode, stdout, stderr };
}
test("OAuth 동의 URL은 PKCE, offline, consent와 두 Gmail scope를 요청한다", () => {
  const url = new URL(
    authorizationUrl(
      "client",
      "http://127.0.0.1/callback",
      "state",
      codeChallenge("verifier"),
    ),
  );
  expect(url.pathname).toBe("/o/oauth2/v2/auth");
  expect(url.searchParams.get("scope")).toContain("gmail.modify");
  expect(url.searchParams.get("scope")).toContain("gmail.settings.basic");
  expect(url.searchParams.get("access_type")).toBe("offline");
  expect(url.searchParams.get("prompt")).toBe("consent");
  expect(url.searchParams.get("code_challenge_method")).toBe("S256");
});
test("PKCE challenge는 RFC 7636 예제와 같다", () => {
  expect(codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe(
    "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
  );
});
test("토큰 교환은 verifier를 form으로 보내고 refresh token만 돌려준다", async () => {
  let sent: RequestInit | undefined;
  const result = await exchangeCode(
    "https://token.invalid",
    "id",
    "secret",
    "code",
    "http://callback",
    "verifier",
    async (_url, init) => {
      sent = init;
      return Response.json({ refresh_token: "fresh" });
    },
  );
  expect(String(sent?.body)).toContain("code_verifier=verifier");
  expect(String(sent?.body)).toContain("grant_type=authorization_code");
  expect(result).toBe("fresh");
});
test("refresh token이 없는 성공 응답은 null이다", async () => {
  for (const answer of [
    {},
    { refresh_token: "" },
    { refresh_token: 1 },
    null,
    [],
  ]) {
    expect(
      await exchangeCode(
        "https://token.invalid",
        "id",
        "secret",
        "code",
        "http://callback",
        "verifier",
        async () => Response.json(answer),
      ),
    ).toBeNull();
  }
});
test("callback은 wrong state를 무시하고 denied state만 실제 HTTP로 받는다", async () => {
  const { exitCode, stdout } = await probeMode("callback");
  expect(exitCode).toBe(0);
  expect(JSON.parse(stdout)).toEqual({
    state: "right",
    error: "access_denied",
  });
});
test("토큰 HTTP 오류의 상세와 응답 본문은 ExchangeError에 싣지 않는다", async () => {
  const { exitCode, stdout } = await probeMode("exchange-error");
  expect(exitCode).toBe(0);
  expect(stdout).toContain("HTTP 400");
  expect(stdout).not.toContain("secret");
});
test("callback 대기는 지정한 상한에서 null로 끝난다", async () => {
  const { exitCode, stdout } = await probeMode("timeout");
  expect(exitCode).toBe(0);
  expect(stdout.trim()).toBe("null");
});
test("CLI 성공은 토큰만 표준 출력에 내고 외부 서비스 연결 안내를 오류 출력에 둔다", async () => {
  const { exitCode, stdout, stderr } = await probeMode("main-success");
  expect(exitCode).toBe(0);
  expect(stdout).toBe("mock-refresh-token\n");
  expect(stderr).toContain(
    "refresh token 입니다. 외부 서비스 연결에 붙여 넣은 뒤 터미널 기록에서 지우세요.",
  );
  expect(stderr).not.toContain("mock-secret");
  expect(stderr).not.toContain("mock-refresh-token");
});
