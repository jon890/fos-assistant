import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

const HOST = "assistant.example.test";
const INPUT_URL = `https://${HOST}/api/browser/screen/input`;

type Handler = (request: Request) => Promise<Response>;

/** 실제 라우트와 출처 판정을 실행한다. 세션과 Control Plane 호출만 바꾸고 불린 경로를 모은다. */
async function loadRoutes() {
  const calls: string[] = [];
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const dependencies: Record<string, unknown> = {
    "@/auth": { auth: async () => ({ user: { email: "user@example.test" } }) },
    "@/lib/control-plane": {
      callControlPlane: async (path: string) => {
        calls.push(path);
        return { ok: true, status: 204, data: null };
      },
      forwardControlPlane: async (path: string) => {
        calls.push(path);
        return { ok: true, response: new Response(null, { status: 204 }) };
      },
      requestControlPlane: async (path: string) => {
        calls.push(path);
        return {
          ok: true,
          response: new Response("data: {}\n\n", { status: 200 }),
        };
      },
    },
    "@/lib/control-plane-result": {
      readControlPlaneResult: (status: number) => ({ ok: true, status }),
    },
  };
  async function load(relative: string) {
    const source = await readFile(new URL(relative, import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText;
    const module = { exports: {} };
    vm.runInNewContext(compiled, {
      module,
      exports: module.exports,
      URL,
      URLSearchParams,
      Blob,
      Response,
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports as Record<string, Handler>;
  }
  dependencies["@/lib/api-response"] = await load(
    "../../web/src/lib/api-response.ts",
  );
  dependencies["@/lib/same-origin"] = await load(
    "../../web/src/lib/same-origin.ts",
  );
  const base = "../../web/src/app/api/browser/";
  return {
    calls,
    sameOrigin: dependencies["@/lib/same-origin"] as unknown as {
      isSameOrigin: (request: Request) => boolean;
    },
    browser: await load(`${base}route.ts`),
    start: await load(`${base}start/route.ts`),
    stop: await load(`${base}stop/route.ts`),
    screen: await load(`${base}screen/route.ts`),
    input: await load(`${base}screen/input/route.ts`),
  };
}

function inputRequest(headers: Record<string, string>): Request {
  return new Request(INPUT_URL, {
    method: "POST",
    headers: { host: HOST, "content-type": "application/json", ...headers },
    body: JSON.stringify({ type: "text", text: "비밀" }),
  });
}

test("같은 출처의 입력은 Control Plane 으로 넘긴다", async () => {
  const routes = await loadRoutes();
  const response = await routes.input.POST(
    inputRequest({ origin: `https://${HOST}` }),
  );
  assert.equal(response.status, 204);
  assert.deepEqual(routes.calls, ["/api/v1/browser/screen/input"]);
});

test("다른 출처의 입력은 Control Plane 을 부르지 않고 403 이다", async () => {
  const routes = await loadRoutes();
  for (const origin of ["https://evil.example.test", "null", "not a url"]) {
    const response = await routes.input.POST(inputRequest({ origin }));
    assert.equal(response.status, 403, origin);
    assert.equal((await response.json()).code, "FORBIDDEN");
  }
  assert.deepEqual(routes.calls, []);
});

test("Origin 이 없으면 넘기되 Sec-Fetch-Site 가 다른 사이트를 알리면 막는다", async () => {
  const routes = await loadRoutes();
  assert.equal((await routes.input.POST(inputRequest({}))).status, 204);
  assert.equal(
    (await routes.input.POST(inputRequest({ "sec-fetch-site": "same-origin" })))
      .status,
    204,
  );
  for (const site of ["cross-site", "same-site"]) {
    const response = await routes.input.POST(
      inputRequest({ "sec-fetch-site": site }),
    );
    assert.equal(response.status, 403, site);
  }
  assert.equal(routes.calls.length, 2);
});

test("프록시가 넘긴 x-forwarded-host 를 Host 보다 먼저 본다", async () => {
  const { sameOrigin } = await loadRoutes();
  const request = (headers: Record<string, string>) =>
    new Request("http://internal/api/browser/start", {
      method: "POST",
      headers,
    });
  assert.equal(
    sameOrigin.isSameOrigin(
      request({
        host: "internal",
        "x-forwarded-host": HOST,
        origin: `https://${HOST}`,
      }),
    ),
    true,
  );
  assert.equal(
    sameOrigin.isSameOrigin(
      request({ host: HOST, "x-forwarded-host": "other.example.test" }),
    ),
    true,
    "Origin 이 없으면 host 를 비교하지 않는다",
  );
  assert.equal(
    sameOrigin.isSameOrigin(
      request({
        host: HOST,
        "x-forwarded-host": "other.example.test",
        origin: `https://${HOST}`,
      }),
    ),
    false,
  );
});

test("상태를 바꾸는 브라우저 라우트는 모두 다른 출처를 막는다", async () => {
  const routes = await loadRoutes();
  const cross = (method: string, url: string) =>
    new Request(url, {
      method,
      headers: { host: HOST, origin: "https://evil.example.test" },
    });
  const handlers: [Handler, Request][] = [
    [routes.browser.POST, cross("POST", `https://${HOST}/api/browser`)],
    [routes.browser.DELETE, cross("DELETE", `https://${HOST}/api/browser`)],
    [routes.start.POST, cross("POST", `https://${HOST}/api/browser/start`)],
    [routes.stop.POST, cross("POST", `https://${HOST}/api/browser/stop`)],
    [
      routes.screen.GET,
      new Request(`https://${HOST}/api/browser/screen`, {
        headers: { host: HOST, "sec-fetch-site": "cross-site" },
      }),
    ],
  ];
  for (const [handler, request] of handlers) {
    assert.equal((await handler(request)).status, 403, request.url);
  }
  assert.deepEqual(routes.calls, []);

  const same = await routes.start.POST(
    new Request(`https://${HOST}/api/browser/start`, {
      method: "POST",
      headers: { host: HOST, origin: `https://${HOST}` },
    }),
  );
  assert.equal(same.status, 204);
  assert.deepEqual(routes.calls, ["/api/v1/browser/start"]);
});
