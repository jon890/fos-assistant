import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";
import * as action from "../../web/src/lib/connector-action.ts";
import * as connection from "../../web/src/lib/connection.ts";

const ID = "0f0e0d0c-0b0a-4908-8706-050403020100";
const catalogs = JSON.parse(await readFile(new URL("../resources/connector-argument-catalog.json", import.meta.url), "utf8"));

function item(argLabels: unknown) {
  return { actionId: ID, connectorId: "gmail", toolName: "send_message", title: "메일 보내기",
    risk: "WRITE", status: "PENDING", argsJson: '{"to":"friend@example.com","body":"합성 본문"}',
    resultText: "옮기면 안 되는 결과", errorCode: null, createdAt: "2026-10-12T00:00:00Z",
    expiresAt: null, grantAllowed: false, hiddenArgs: false, argLabels, secret: "버릴 칸" };
}

/** 실제 조회·승인·거절 라우트를 실행한다. 인증이 필요한 Control Plane 호출만 대역한다. */
async function callRoute(kind: "list" | "approve" | "reject", value: unknown) {
  const webRequire = createRequire(new URL("../../web/package.json", import.meta.url));
  const calls: { path: string; init: unknown }[] = [];
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": { callControlPlane: async (path: string, init: unknown) => {
      calls.push({ path, init });
      return { ok: true, status: 200, data: value };
    } },
    "@/lib/connector-action": action,
    "@/lib/connection": connection,
    "@/lib/conversation-id": { isConversationId: () => true },
    "@/lib/json-body": { readJsonBody: async (request: Request) => ({ ok: true, body: await request.json() }) },
    "@/lib/api-response": {},
  };
  async function load(relative: string) {
    const source = await readFile(new URL(relative, import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
    } }).outputText;
    const module = { exports: {} };
    vm.runInNewContext(compiled, { module, exports: module.exports, TextEncoder,
      require: (name: string) => dependencies[name] ?? webRequire(name) });
    return module.exports;
  }
  dependencies["@/lib/connector-action-route"] = await load("../../web/src/lib/connector-action-route.ts");
  const relative = kind === "list"
    ? "../../web/src/app/api/chat/conversations/[conversationId]/connector-actions/route.ts"
    : `../../web/src/app/api/connector-actions/[actionId]/${kind}/route.ts`;
  const route = await load(relative) as {
    GET: (request: Request, context: unknown) => Promise<Response>;
    POST: (request: Request, context: unknown) => Promise<Response>;
  };
  const request = new Request("https://example.test/action", kind === "approve"
    ? { method: "POST", body: JSON.stringify({ grant: null, argLabels: { to: "요청의 가짜 이름" } }) }
    : undefined);
  const response = await (kind === "list" ? route.GET : route.POST)(request,
    { params: Promise.resolve({ actionId: ID, conversationId: ID }) });
  return { response, calls };
}

test("Python producer의 표시 이름은 조회·승인·거절 응답에 남고 원문 결과는 버린다", async () => {
  for (const catalog of catalogs) {
    const labels = catalog.tools[catalog.id === "gmail" ? "send_message" : "save_draft"].arg_labels;
    for (const kind of ["list", "approve", "reject"] as const) {
      const source = item(labels);
      const { response, calls } = await callRoute(kind, kind === "list" ? [source] : source);
      assert.equal(response.status, 200);
      const body = await response.json();
      const safe = kind === "list" ? body[0] : body;
      assert.deepEqual(safe.argLabels, labels);
      assert.equal(safe.argsJson, source.argsJson);
      assert.equal(safe.hiddenArgs, false);
      assert.equal(safe.grantAllowed, false);
      assert.equal(safe.resultText, null);
      assert.equal(safe.secret, undefined);
      assert.equal(calls[0].path, kind === "list"
        ? `/api/v1/chat/conversations/${ID}/connector-actions` : `/api/v1/connector-actions/${ID}/${kind}`);
      if (kind === "approve") assert.equal(JSON.stringify(calls[0].init), '{"method":"POST","body":{"grant":null}}');
    }
  }
});

test("옛 응답과 잘못된 표시 이름도 승인 줄을 유지한다", async () => {
  for (const labels of [undefined, null, [], 1, { to: 1 }, { to: {} }, { to: "줄\n바꿈" },
    { a: "b", b: "다름" }, { a: "겹침", b: "겹침" }, JSON.parse('{"__proto__":"이름"}')]) {
    const { response } = await callRoute("list", [item(labels)]);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json())[0].argLabels, {});
  }
});
