import { expect, test } from "bun:test";
import { join } from "node:path";

const script = join(import.meta.dir, "../scripts/get_refresh_token.py");
const probe = join(import.meta.dir, "../scripts/oauth-test-driver.py");

async function python(code: string) {
  const child = Bun.spawn(["python3", "-c", code, script], {
    stdout: "pipe",
    stderr: "pipe",
  });
  return {
    exitCode: await child.exited,
    stdout: await new Response(child.stdout).text(),
    stderr: await new Response(child.stderr).text(),
  };
}

async function probeMode(mode: string) {
  const child = Bun.spawn(["python3", probe, mode, script], {
    stdout: "pipe",
    stderr: "pipe",
  });
  return {
    exitCode: await child.exited,
    stdout: await new Response(child.stdout).text(),
    stderr: await new Response(child.stderr).text(),
  };
}

test("OAuth 동의 URL은 PKCE, offline, consent와 두 Gmail scope를 요청한다", async () => {
  const { exitCode, stdout } = await python(`
import importlib.util, sys, urllib.parse
spec = importlib.util.spec_from_file_location("oauth", sys.argv[1]); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
url = module.authorization_url("client", "http://127.0.0.1/callback", "state", module.code_challenge("verifier"))
print(urllib.parse.urlsplit(url).path)
print(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query))
`);
  expect(exitCode).toBe(0);
  expect(stdout).toContain("/o/oauth2/v2/auth");
  expect(stdout).toContain("gmail.modify");
  expect(stdout).toContain("gmail.settings.basic");
  expect(stdout).toContain("'access_type': ['offline']");
  expect(stdout).toContain("'code_challenge_method': ['S256']");
});

test("PKCE challenge는 RFC 7636 예제와 같다", async () => {
  const { exitCode, stdout } = await python(`
import importlib.util, sys
spec = importlib.util.spec_from_file_location("oauth", sys.argv[1]); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
print(module.code_challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
`);
  expect(exitCode).toBe(0);
  expect(stdout.trim()).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
});

test("토큰 교환은 verifier를 form으로 보내고 refresh token만 돌려준다", async () => {
  const { exitCode, stdout } = await python(`
import importlib.util, sys, urllib.request, io
spec = importlib.util.spec_from_file_location("oauth", sys.argv[1]); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
class Answer:
  def __enter__(self): return self
  def __exit__(self, *args): return False
  def read(self): return b'{"refresh_token":"fresh"}'
def open(request, timeout):
  print(request.data.decode("ascii"))
  return Answer()
urllib.request.urlopen = open
print(module.exchange_code("https://token.invalid", "id", "secret", "code", "http://callback", "verifier"))
`);
  expect(exitCode).toBe(0);
  expect(stdout).toContain("code_verifier=verifier");
  expect(stdout).toContain("grant_type=authorization_code");
  expect(stdout).toEndWith("fresh\n");
});

test("refresh token이 없는 성공 응답은 None이다", async () => {
  const { exitCode, stdout } = await python(`
import importlib.util, sys, urllib.request, urllib.error
spec = importlib.util.spec_from_file_location("oauth", sys.argv[1]); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
class Answer:
  def __enter__(self): return self
  def __exit__(self, *args): return False
  def read(self): return b'{}'
urllib.request.urlopen = lambda *args, **kwargs: Answer()
print(module.exchange_code("https://token.invalid", "id", "secret", "code", "http://callback", "verifier"))
`);
  expect(exitCode).toBe(0);
  expect(stdout.trim()).toBe("None");
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

test("callback 대기는 실제로 300초 상한에서 None으로 끝난다", async () => {
  const { exitCode, stdout } = await probeMode("timeout");
  expect(exitCode).toBe(0);
  expect(stdout.trim()).toBe("None");
});
