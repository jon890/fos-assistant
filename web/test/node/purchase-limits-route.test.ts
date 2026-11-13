import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../node_modules/typescript/lib/typescript.js";
import * as action from "../../src/lib/connector-action.ts";
import * as connection from "../../src/lib/connection.ts";

const LIMITS = {
  orderKrw: 50000,
  dailyKrw: 100000,
  spentTodayKrw: 0,
  remainingTodayKrw: 100000,
  extra: "버릴 칸",
};

/** 실제 구매 상한 라우트를 실행한다. 인증이 필요한 Control Plane 호출만 대역한다. */
async function callRoute(
  kind: "get" | "put",
  value: unknown,
  name = "order",
  body: unknown = { value: 50000 },
) {
  const webRequire = createRequire(
    new URL("../../package.json", import.meta.url),
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
    "../../src/lib/content-draft-snapshot.ts",
  );
  dependencies["@/lib/decimal"] = await load("../../src/lib/decimal.ts");
  dependencies["@/lib/financial-limits"] = await load(
    "../../src/lib/financial-limits.ts",
  );
  dependencies["@/lib/financial-limits-route"] = await load(
    "../../src/lib/financial-limits-route.ts",
  );
  dependencies["@/lib/purchase-limits"] = await load(
    "../../src/lib/purchase-limits.ts",
  );
  dependencies["@/lib/purchase-limits-route"] = await load(
    "../../src/lib/purchase-limits-route.ts",
  );
  dependencies["@/lib/connector-action-route"] = await load(
    "../../src/lib/connector-action-route.ts",
  );
  const route = (await load(
    kind === "get"
      ? "../../src/app/api/purchase-limits/route.ts"
      : "../../src/app/api/purchase-limits/[name]/route.ts",
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

test("구매 상한 응답은 계약의 칸만 옮긴다", async () => {
  const { response, calls } = await callRoute("get", LIMITS);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    orderKrw: 50000,
    dailyKrw: 100000,
    spentTodayKrw: 0,
    remainingTodayKrw: 100000,
  });
  assert.equal(calls[0].path, "/api/v1/purchase-limits");
});

test("구매 상한을 바꾸면 이름과 원 단위 정수만 보낸다", async () => {
  const { response, calls } = await callRoute("put", LIMITS, "daily", {
    value: 0,
  });
  assert.equal(response.status, 200);
  assert.equal(calls[0].path, "/api/v1/purchase-limits/daily");
  assert.equal(
    JSON.stringify(calls[0].init),
    '{"method":"PUT","body":{"value":0}}',
  );
});

test("모르는 이름과 정수가 아닌 값은 Control Plane 을 부르지 않고 400 이다", async () => {
  for (const [name, body] of [
    ["weekly", { value: 1 }],
    ["order_amount_krw", { value: 1 }],
    ["order", { value: "50000" }],
    ["order", { value: 1.5 }],
    ["order", { value: -1 }],
    ["order", { value: null }],
    ["order", { value: 1_000_000_000_000 }],
    ["order", {}],
  ] as const) {
    const { response, calls } = await callRoute("put", LIMITS, name, body);
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  }
});

test("모양이 틀린 응답은 고정 오류로 돌린다", async () => {
  for (const broken of [
    { ...LIMITS, orderKrw: "50000" },
    { ...LIMITS, dailyKrw: -1 },
    { ...LIMITS, spentTodayKrw: 1.5 },
    { ...LIMITS, remainingTodayKrw: null },
    null,
  ]) {
    const { response } = await callRoute("get", broken);
    assert.equal(response.status, 502);
  }
});
