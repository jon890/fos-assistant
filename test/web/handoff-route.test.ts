import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";
import * as handoff from "../../web/src/lib/handoff.ts";
import * as conversationId from "../../web/src/lib/conversation-id.ts";

const CONVERSATION = "0190f5b4-0000-7000-8000-000000000001";

const ROW = {
  handoffId: "0190f5b4-0000-7000-8000-0000000000aa",
  connectorId: "naver-shopping",
  connectorTitle: "네이버 쇼핑",
  need: "LOGIN",
  tab: "ABCDEF0123456789",
  createdAt: "2026-11-10T00:00:00Z",
};

/** 실제 GET 라우트를 실행한다. 세션이 필요한 Control Plane 호출과 오류 응답 도우미만 바꾼다. */
async function getHandoffs(rows: unknown): Promise<Response> {
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string) => {
        assert.equal(
          path,
          `/api/v1/chat/conversations/${CONVERSATION}/handoffs`,
        );
        return { ok: true, status: 200, data: rows };
      },
    },
    "@/lib/connector-action-route": {
      connectorActionResponse: () => new Response(null, { status: 502 }),
      invalidConnectorActionRequest: () => new Response(null, { status: 400 }),
    },
    "@/lib/conversation-id": conversationId,
    "@/lib/handoff": handoff,
  };
  const source = await readFile(
    new URL(
      "../../web/src/app/api/chat/conversations/[conversationId]/handoffs/route.ts",
      import.meta.url,
    ),
    "utf8",
  );
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
    require: (name: string) => dependencies[name] ?? webRequire(name),
  });
  const route = module.exports as {
    GET: (
      request: Request,
      context: { params: Promise<{ conversationId: string }> },
    ) => Promise<Response>;
  };
  return route.GET(new Request("http://localhost/"), {
    params: Promise.resolve({ conversationId: CONVERSATION }),
  });
}

test("넘기기 라우트는 계약 안의 줄만 돌려주고 틀린 단계와 탭 모양의 줄은 버린다", async () => {
  const response = await getHandoffs([
    {
      ...ROW,
      handoffId: "0190f5b4-0000-7000-8000-0000000000ab",
      need: "PAYMENT",
    },
    {
      ...ROW,
      handoffId: "0190f5b4-0000-7000-8000-0000000000ac",
      tab: "../tab",
    },
    ROW,
  ]);

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [ROW]);
});

test("넘기기 라우트는 목록이 아닌 응답을 빈 목록으로 돌려준다", async () => {
  const response = await getHandoffs({ need: "LOGIN" });

  assert.deepEqual(await response.json(), []);
});
