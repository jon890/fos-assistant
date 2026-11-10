import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

type Call = { path: string; init: { method?: string; body?: unknown } };

/** 실제 PUT 라우트를 실행한다. Control Plane 호출만 바꾼다. */
async function put(
  id: string,
  body: unknown,
  status = 204,
): Promise<{ response: Response; calls: Call[] }> {
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const calls: Call[] = [];
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string, init: Call["init"]) => {
        calls.push({ path, init });
        return status === 204
          ? { ok: true, status, data: null }
          : { ok: false, status, code: "NOT_FOUND", message: "없어요." };
      },
    },
    "@/lib/api-response": {
      errorResponse: (code: string, message: string, httpStatus: number) =>
        Response.json({ code, message }, { status: httpStatus }),
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
      Response,
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports;
  }
  dependencies["@/lib/json-body"] = await load(
    "../../web/src/lib/json-body.ts",
  );
  const route = (await load(
    "../../web/src/app/api/memory/maintenance-candidates/[id]/review/route.ts",
  )) as {
    PUT: (request: Request, context: unknown) => Promise<Response>;
  };
  const response = await route.PUT(
    new Request("http://localhost/x", {
      method: "PUT",
      body: JSON.stringify(body),
    }),
    { params: Promise.resolve({ id }) },
  );
  return { response, calls };
}

test("확인 결과는 CORRECT 와 INCORRECT 만 Control Plane 에 넘긴다", async () => {
  for (const review of ["CORRECT", "INCORRECT"]) {
    const { response, calls } = await put("71", { review, extra: "버림" });
    assert.equal(response.status, 204);
    assert.deepEqual(JSON.parse(JSON.stringify(calls)), [
      {
        path: "/api/v1/memory/maintenance-candidates/71/review",
        init: { method: "PUT", body: { review } },
      },
    ]);
  }
});

test("모르는 확인 결과와 숫자가 아닌 번호는 Control Plane 을 부르지 않고 400 이다", async () => {
  for (const [id, body] of [
    ["71", { review: "ARCHIVE" }],
    ["71", {}],
    ["abc", { review: "CORRECT" }],
  ] as const) {
    const { response, calls } = await put(id, body);
    assert.equal(response.status, 400);
    assert.deepEqual(calls, []);
  }
});

test("없거나 고칠 수 없는 후보의 404 를 그대로 돌려준다", async () => {
  const { response } = await put("99", { review: "CORRECT" }, 404);
  assert.equal(response.status, 404);
});
