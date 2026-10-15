import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";
import * as action from "../../web/src/lib/connector-action.ts";
import * as connection from "../../web/src/lib/connection.ts";

const LIMITS = {
  available: true,
  limits: [
    {
      name: "order_amount_usd",
      currency: "USD",
      label: "한 건 달러 금액",
      value: "500",
      maximum: "1000",
      custom: true,
      secret: "버릴 칸",
    },
  ],
  spentToday: { KRW: "0", USD: "250" },
  remainingToday: { KRW: "300000", USD: "750" },
  extra: "버릴 칸",
};

/** 실제 상한 라우트를 실행한다. 인증이 필요한 Control Plane 호출만 대역한다. */
async function callRoute(
  kind: "get" | "put",
  value: unknown,
  name = "order_amount_usd",
  body: unknown = { value: "500" },
) {
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const calls: { path: string; init: unknown }[] = [];
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string, init: unknown) => {
        calls.push({ path, init });
        return { ok: true, status: 200, data: value };
      },
    },
    "@/lib/connector-action": action,
    "@/lib/connection": connection,
    "@/lib/conversation-id": {
      isConversationId: () => true,
      isPublicId: () => true,
    },
    "@/lib/json-body": {
      readJsonBody: async (request: Request) => ({
        ok: true,
        body: await request.json(),
      }),
    },
    "@/lib/api-response": {
      errorResponse: (code: string, message: string, status: number) =>
        Response.json({ code, message }, { status }),
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
      TextEncoder,
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports;
  }
  dependencies["@/lib/content-draft-snapshot"] = await load(
    "../../web/src/lib/content-draft-snapshot.ts",
  );
  dependencies["@/lib/financial-limits"] = await load(
    "../../web/src/lib/financial-limits.ts",
  );
  dependencies["@/lib/financial-limits-route"] = await load(
    "../../web/src/lib/financial-limits-route.ts",
  );
  dependencies["@/lib/connector-action-route"] = await load(
    "../../web/src/lib/connector-action-route.ts",
  );
  const route = (await load(
    kind === "get"
      ? "../../web/src/app/api/financial-limits/route.ts"
      : "../../web/src/app/api/financial-limits/[name]/route.ts",
  )) as {
    GET: () => Promise<Response>;
    PUT: (request: Request, context: unknown) => Promise<Response>;
  };
  const response =
    kind === "get"
      ? await route.GET()
      : await route.PUT(
          new Request("https://example.test/limits", {
            method: "PUT",
            body: JSON.stringify(body),
          }),
          { params: Promise.resolve({ name }) },
        );
  return { response, calls };
}

test("상한 응답은 계약의 칸만 옮긴다", async () => {
  const { response, calls } = await callRoute("get", LIMITS);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.extra, undefined);
  assert.equal(body.limits[0].secret, undefined);
  assert.equal(body.limits[0].value, "500");
  assert.deepEqual(body.remainingToday, { KRW: "300000", USD: "750" });
  assert.equal(calls[0].path, "/api/v1/financial-limits");
});

test("상한을 바꾸면 이름과 값만 보내고, null 은 최대치로 되돌리는 요청이다", async () => {
  const { response, calls } = await callRoute("put", LIMITS);
  assert.equal(response.status, 200);
  assert.equal(calls[0].path, "/api/v1/financial-limits/order_amount_usd");
  assert.equal(
    JSON.stringify(calls[0].init),
    '{"method":"PUT","body":{"value":"500"}}',
  );
  const reset = await callRoute("put", LIMITS, "daily_amount_krw", {
    value: null,
  });
  assert.equal(
    JSON.stringify(reset.calls[0].init),
    '{"method":"PUT","body":{"value":null}}',
  );
});

test("모르는 이름과 십진 글이 아닌 값은 Control Plane 을 부르지 않고 400 이다", async () => {
  for (const [name, body] of [
    ["weekly_amount", { value: "1" }],
    ["order_amount_usd", { value: "1e3" }],
    ["order_amount_usd", { value: -1 }],
    ["order_amount_usd", { value: "1.1234567" }],
  ] as const) {
    const { response, calls } = await callRoute("put", LIMITS, name, body);
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  }
});

test("모양이 틀린 상한 응답은 고정 오류로 돌린다", async () => {
  const { response } = await callRoute("get", {
    ...LIMITS,
    remainingToday: { KRW: "많이", USD: "1" },
  });
  assert.equal(response.status, 502);
  assert.equal((await response.json()).code, "INTERNAL_ERROR");
});
