import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../node_modules/typescript/lib/typescript.js";
import { toolText } from "../../src/lib/connector-tool-text.ts";
import * as connection from "../../src/lib/connection.ts";
import { AGENT_CODE_PATTERN } from "../../src/lib/agent.ts";

const CODE = "a".repeat(12);

function view(change: Record<string, unknown> = {}) {
  return {
    connectorId: "demo-notes",
    title: "검사용 메모",
    connectionStatus: "READY",
    bound: true,
    status: "PENDING",
    restartRequired: false,
    toolCount: 2,
    skills: ["demo-runner"],
    ...change,
  };
}

/** 실제 붙이기 PUT 라우트와 응답 정제를 실행한다. Control Plane 호출만 바꾼다. */
async function bind(answer: unknown): Promise<Response> {
  const webRequire = createRequire(
    new URL("../../package.json", import.meta.url),
  );
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string) => {
        assert.equal(path, `/api/v1/agents/${CODE}/connections/demo-notes`);
        return { ok: true, status: 200, data: answer };
      },
    },
    "@/lib/connection": connection,
    "@/lib/connector-tool-text": { toolText },
    "@/lib/agent": { AGENT_CODE_PATTERN },
    "@/lib/json-body": {},
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
      TextEncoder,
      exports: module.exports,
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports;
  }
  dependencies["@/lib/connection-route"] = await load(
    "../../src/lib/connection-route.ts",
  );
  const route = (await load(
    "../../src/app/api/agents/[code]/connections/[connectorId]/route.ts",
  )) as {
    PUT: (request: Request, context: unknown) => Promise<Response>;
  };
  return route.PUT(new Request("http://localhost/"), {
    params: Promise.resolve({ code: CODE, connectorId: "demo-notes" }),
  });
}

test("붙이기 라우트는 빠진 스킬의 이름과 까닭을 그대로 옮긴다", async () => {
  const skippedSkills = [
    { name: "demo-runner", reasons: ["terminal_off", "sandbox_env_missing"] },
    { name: "demo-report", reasons: ["name_taken"] },
  ];
  const response = await bind(view({ skippedSkills }));
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).skippedSkills, skippedSkills);
});

test("빠진 스킬 칸이 없는 옛 Control Plane 응답은 빈 목록이다", async () => {
  const response = await bind(view());
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).skippedSkills, []);
});
