import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";
import * as conversationId from "../../web/src/lib/conversation-id.ts";

const ID = "0f0e0d0c-0b0a-4908-8706-050403020100";
const OTHER = "1f0e0d0c-0b0a-4908-8706-050403020100";
const ROUTES = "../../web/src/app/api/chat/conversations/[conversationId]";

/** 원고 GET 라우트를 실제로 실행한다. 인증이 필요한 Control Plane 호출만 대역한다. */
async function callRoute(
  relative: string,
  params: Record<string, string>,
  answer: unknown,
  url = "https://example.test/route",
) {
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const calls: string[] = [];
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string) => {
        calls.push(path);
        return answer;
      },
    },
    "@/lib/conversation-id": conversationId,
    "@/lib/api-response": {
      errorResponse: (code: string, message: string, status: number) =>
        Response.json({ code, message }, { status }),
    },
    "next/server": { NextResponse: Response },
  };
  async function load(path: string) {
    const source = await readFile(new URL(path, import.meta.url), "utf8");
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
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports;
  }
  dependencies["@/lib/content-draft-route"] = await load(
    "../../web/src/lib/content-draft-route.ts",
  );
  const route = (await load(`${ROUTES}/${relative}`)) as {
    GET: (request: Request, context: unknown) => Promise<Response>;
  };
  const response = await route.GET(new Request(url), {
    params: Promise.resolve(params),
  });
  return { response, calls };
}

const SNAPSHOT = "content-drafts/[draftId]/snapshots/[snapshotId]/route.ts";
const JOB = "content-drafts/[draftId]/snapshots/[snapshotId]/job/route.ts";
const JOBS = "content-draft-jobs/route.ts";

test("snapshot 과 job 은 세 UUID 경로로만 Control Plane 을 부른다", async () => {
  const params = { conversationId: ID, draftId: OTHER, snapshotId: ID };
  const ok = { ok: true, status: 200, data: { revision: 3 } };
  for (const [relative, suffix] of [
    [SNAPSHOT, ""],
    [JOB, "/job"],
  ]) {
    const { response, calls } = await callRoute(relative, params, ok);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { revision: 3 });
    assert.deepEqual(calls, [
      `/api/v1/chat/conversations/${ID}/content-drafts/${OTHER}/snapshots/${ID}${suffix}`,
    ]);
    const bad = await callRoute(relative, { ...params, draftId: "../x" }, ok);
    assert.equal(bad.response.status, 400);
    assert.deepEqual(bad.calls, []);
  }
});

test("STALE·삭제·남의 것은 code 를 넘기고 그 밖의 code 와 원문은 넘기지 않는다", async () => {
  const params = { conversationId: ID, draftId: ID, snapshotId: ID };
  for (const [status, code, expected] of [
    [409, "CONTENT_DRAFT_STALE", "CONTENT_DRAFT_STALE"],
    [410, "CONTENT_DRAFT_GONE", "CONTENT_DRAFT_GONE"],
    [404, "CONVERSATION_NOT_FOUND", "CONVERSATION_NOT_FOUND"],
    [503, "CONTENT_DRAFT_UNAVAILABLE", "CONTENT_DRAFT_UNAVAILABLE"],
    [500, "SECRET_INTERNAL", "INTERNAL_ERROR"],
  ] as const) {
    const { response } = await callRoute(SNAPSHOT, params, {
      ok: false,
      status,
      code,
      message: "원고 본문 /home/secret",
    });
    assert.equal(response.status, status);
    const body = await response.json();
    assert.equal(body.code, expected);
    assert.doesNotMatch(body.message, /secret/);
  }
});

test("job 목록은 beforeSnapshotId 만 UUID 로 검사해 넘긴다", async () => {
  const ok = {
    ok: true,
    status: 200,
    data: { items: [], nextBeforeSnapshotId: null },
  };
  const first = await callRoute(JOBS, { conversationId: ID }, ok);
  assert.deepEqual(first.calls, [
    `/api/v1/chat/conversations/${ID}/content-draft-jobs`,
  ]);
  const next = await callRoute(
    JOBS,
    { conversationId: ID },
    ok,
    `https://example.test/route?beforeSnapshotId=${OTHER}&owner=1`,
  );
  assert.deepEqual(next.calls, [
    `/api/v1/chat/conversations/${ID}/content-draft-jobs?beforeSnapshotId=${OTHER}`,
  ]);
  const bad = await callRoute(
    JOBS,
    { conversationId: ID },
    ok,
    "https://example.test/route?beforeSnapshotId=x%26owner%3D1",
  );
  assert.equal(bad.response.status, 400);
  assert.deepEqual(bad.calls, []);
});
