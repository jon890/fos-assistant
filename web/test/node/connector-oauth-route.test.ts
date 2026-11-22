import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../node_modules/typescript/lib/typescript.js";
import { toolText } from "../../src/lib/connector-tool-text.ts";
import * as connection from "../../src/lib/connection.ts";
import * as connectionOAuth from "../../src/lib/connection-oauth.ts";
import { AGENT_CODE_PATTERN } from "../../src/lib/agent.ts";

type Call = { path: string; body: unknown };
type Reply = { ok: true; status: number; data: unknown } | { ok: false; status: number; code: string; message: string };

/** 실제 단추 연결 라우트와 응답 정제를 실행한다. 세션이 필요한 Control Plane 호출만 바꾼다. */
async function routes(reply: Reply) {
  const calls: Call[] = [];
  const webRequire = createRequire(new URL("../../package.json", import.meta.url));
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string, init: { body?: unknown }) => {
        calls.push({ path, body: init.body });
        return reply;
      },
    },
    "@/lib/connection": connection,
    "@/lib/connection-oauth": connectionOAuth,
    "@/lib/connector-tool-text": { toolText },
    "@/lib/agent": { AGENT_CODE_PATTERN },
  };
  async function load(relative: string) {
    const source = await readFile(new URL(relative, import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText;
    const module = { exports: {} };
    vm.runInNewContext(compiled, {
      module,
      URL,
      TextEncoder,
      exports: module.exports,
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports;
  }
  dependencies["@/lib/api-response"] = await load("../../src/lib/api-response.ts");
  dependencies["@/lib/json-body"] = await load("../../src/lib/json-body.ts");
  dependencies["@/lib/same-origin"] = await load("../../src/lib/same-origin.ts");
  dependencies["@/lib/connection-route"] = await load("../../src/lib/connection-route.ts");
  dependencies["@/lib/connection-oauth-route"] = await load("../../src/lib/connection-oauth-route.ts");
  const start = (await load("../../src/app/api/connections/[id]/oauth/start/route.ts")) as {
    POST: (request: Request, context: { params: Promise<{ id: string }> }) => Promise<Response>;
  };
  const complete = (await load("../../src/app/api/connections/oauth/complete/route.ts")) as {
    POST: (request: Request) => Promise<Response>;
  };
  return { calls, start: start.POST, complete: complete.POST };
}

function post(url: string, body: unknown, headers: Record<string, string>) {
  return new Request(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

/** vm 안에서 만든 객체는 다른 realm 이라 구조만 비교한다. */
function plain(value: unknown) {
  return JSON.parse(JSON.stringify(value)) as unknown;
}

const SAME = { origin: "https://home.example", host: "home.example" };
const AUTHORIZE = "https://github.com/apps/demo/installations/new?state=s";

test("시작은 요청이 온 공개 주소로 돌아오는 주소를 만들고 단추 밖 칸 값을 넘긴다", async () => {
  const route = await routes({ ok: true, status: 200, data: { authorizeUrl: AUTHORIZE, extra: "x" } });
  const response = await route.start(post("http://internal:3000/api/connections/career/oauth/start", { values: { repo: "me/site" } }, SAME), {
    params: Promise.resolve({ id: "career" }),
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { authorizeUrl: AUTHORIZE });
  assert.deepEqual(plain(route.calls), [
    {
      path: "/api/v1/connections/career/oauth/start",
      body: { redirectUri: "https://home.example/connections/oauth/callback", values: { repo: "me/site" } },
    },
  ]);
});

test("Origin 이 없으면 앞단이 넘긴 host 와 scheme 으로 돌아오는 주소를 만든다", async () => {
  const route = await routes({ ok: true, status: 200, data: { authorizeUrl: AUTHORIZE } });
  await route.start(
    post("http://internal:3000/api/connections/career/oauth/start", { values: {} }, {
      host: "internal:3000",
      "x-forwarded-host": "home.example",
      "x-forwarded-proto": "https",
    }),
    { params: Promise.resolve({ id: "career" }) },
  );
  assert.equal((route.calls[0].body as { redirectUri: string }).redirectUri, "https://home.example/connections/oauth/callback");
});

test("시작은 다른 출처와 틀린 id 를 Control Plane 에 보내지 않는다", async () => {
  const route = await routes({ ok: true, status: 200, data: { authorizeUrl: AUTHORIZE } });
  const cross = await route.start(post("https://home.example/x", { values: {} }, { origin: "https://evil.example", host: "home.example" }), {
    params: Promise.resolve({ id: "career" }),
  });
  assert.equal(cross.status, 403);
  const invalid = await route.start(post("https://home.example/x", { values: {} }, SAME), {
    params: Promise.resolve({ id: "../admin" }),
  });
  assert.equal(invalid.status, 400);
  assert.equal(route.calls.length, 0);
});

test("앱이 설정되지 않았다는 오류는 그 코드로, https 가 아닌 로그인 주소는 연결 실패로 바꾼다", async () => {
  const notConfigured = await routes({ ok: false, status: 409, code: "CONNECTOR_OAUTH_NOT_CONFIGURED", message: "raw" });
  const response = await notConfigured.start(post("https://home.example/x", { values: {} }, SAME), {
    params: Promise.resolve({ id: "career" }),
  });
  assert.equal(response.status, 409);
  assert.equal(((await response.json()) as { code: string }).code, "CONNECTOR_OAUTH_NOT_CONFIGURED");

  const badUrl = await routes({ ok: true, status: 200, data: { authorizeUrl: "javascript:alert(1)" } });
  const bad = await badUrl.start(post("https://home.example/x", { values: {} }, SAME), {
    params: Promise.resolve({ id: "career" }),
  });
  assert.equal(bad.status, 502);
  assert.equal(((await bad.json()) as { code: string }).code, "CONNECTOR_OPERATION_FAILED");
});

test("돌아오기는 검색어 네 칸만 넘기고 결과의 계약 칸만 옮긴다", async () => {
  const route = await routes({
    ok: true,
    status: 200,
    data: { outcome: "VERIFY_FAILED", connectorId: "career", authorizeUrl: null, reason: "CONNECTOR_CREDENTIAL_REJECTED", token: "x" },
  });
  const response = await route.complete(
    post("https://home.example/api/connections/oauth/complete", { state: "s", code: "c", error: null, setupAction: "install", other: "y" }, SAME),
  );
  assert.deepEqual(await response.json(), {
    outcome: "VERIFY_FAILED",
    connectorId: "career",
    authorizeUrl: null,
    reason: "CONNECTOR_CREDENTIAL_REJECTED",
  });
  assert.deepEqual(plain(route.calls), [
    { path: "/api/v1/connections/oauth/complete", body: { state: "s", code: "c", error: null, setupAction: "install" } },
  ]);
});

test("돌아오기는 문자열이 아닌 검색어와 다른 출처를 거절한다", async () => {
  const route = await routes({ ok: true, status: 200, data: { outcome: "CONNECTED", connectorId: "career" } });
  const invalid = await route.complete(post("https://home.example/x", { state: 1 }, SAME));
  assert.equal(invalid.status, 400);
  const cross = await route.complete(post("https://home.example/x", { state: "s" }, { origin: "https://evil.example", host: "home.example" }));
  assert.equal(cross.status, 403);
  assert.equal(route.calls.length, 0);
});
