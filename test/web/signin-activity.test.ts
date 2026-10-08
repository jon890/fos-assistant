import assert from "node:assert/strict";
import test from "node:test";
import { jwtVerify } from "../../web/node_modules/jose/dist/webapi/index.js";
import { recordSignIn } from "../../web/src/lib/signin-activity.ts";

const originalFetch = globalThis.fetch;
const originalSecret = process.env.ASSISTANT_JWT_SECRET;
const originalBaseUrl = process.env.CONTROL_PLANE_BASE_URL;

function restore(): void {
  globalThis.fetch = originalFetch;
  if (originalSecret === undefined) delete process.env.ASSISTANT_JWT_SECRET;
  else process.env.ASSISTANT_JWT_SECRET = originalSecret;
  if (originalBaseUrl === undefined) delete process.env.CONTROL_PLANE_BASE_URL;
  else process.env.CONTROL_PLANE_BASE_URL = originalBaseUrl;
}

test.afterEach(restore);

test("로그인 완료는 용도 전용 서명과 이메일만 보내고 응답을 기다린다", async () => {
  process.env.ASSISTANT_JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
  process.env.CONTROL_PLANE_BASE_URL = "https://control.example/";
  let captured: RequestInit | undefined;
  let address = "";
  globalThis.fetch = async (input, init) => {
    address = String(input);
    captured = init;
    return new Response(null, { status: 204 });
  };

  await recordSignIn("user@example.com");

  assert.equal(address, "https://control.example/api/v1/signin/completed");
  assert.equal(captured?.method, "POST");
  assert.equal(captured?.cache, "no-store");
  assert.ok(captured?.signal instanceof AbortSignal);
  assert.deepEqual(JSON.parse(String(captured?.body)), { email: "user@example.com" });
  const token = new Headers(captured?.headers).get("Authorization")?.replace("Bearer ", "");
  assert.ok(token);
  const verified = await jwtVerify(
    token,
    new TextEncoder().encode(process.env.ASSISTANT_JWT_SECRET),
  );
  assert.equal(verified.payload.purpose, "signin");
  assert.equal(verified.payload.sub, undefined);
  assert.equal(typeof verified.payload.exp, "number");
  assert.equal(typeof verified.payload.iat, "number");
  assert.equal(verified.payload.exp - verified.payload.iat, 120);
});

test("로그인 완료가 실패 상태를 돌려주면 Auth.js 이벤트까지 실패를 보낸다", async () => {
  process.env.ASSISTANT_JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
  process.env.CONTROL_PLANE_BASE_URL = "https://control.example";
  globalThis.fetch = async () => new Response(null, { status: 401 });
  await assert.rejects(recordSignIn("user@example.com"), /401/);
});

test("로그인 완료 요청의 네트워크 오류를 숨기지 않는다", async () => {
  process.env.ASSISTANT_JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
  process.env.CONTROL_PLANE_BASE_URL = "https://control.example";
  globalThis.fetch = async () => { throw new Error("network down"); };
  await assert.rejects(recordSignIn("user@example.com"), /network down/);
});
