import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";
import * as action from "../../web/src/lib/connector-action.ts";
import * as connection from "../../web/src/lib/connection.ts";

const ID = "0f0e0d0c-0b0a-4908-8706-050403020100";
const catalogs = JSON.parse(
  await readFile(
    new URL("../resources/connector-argument-catalog.json", import.meta.url),
    "utf8",
  ),
);

function item(argLabels: unknown) {
  return {
    actionId: ID,
    connectorId: "gmail",
    toolName: "send_message",
    title: "메일 보내기",
    risk: "WRITE",
    status: "PENDING",
    argsJson: '{"to":"friend@example.com","body":"합성 본문"}',
    resultText: "옮기면 안 되는 결과",
    errorCode: null,
    createdAt: "2026-10-12T00:00:00Z",
    expiresAt: null,
    grantAllowed: false,
    hiddenArgs: false,
    argLabels,
    secret: "버릴 칸",
  };
}

/** 실제 조회·승인·거절 라우트를 실행한다. 인증이 필요한 Control Plane 호출만 대역한다. */
async function callRoute(
  kind:
    | "list"
    | "approve"
    | "reject"
    | "conversationGrants"
    | "revokeConversationGrant",
  value: unknown,
  approveBody: unknown = { grant: null, argLabels: { to: "요청의 가짜 이름" } },
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
      isPublicId: (value: string) => /^[0-9a-f-]{36}$/.test(value),
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
  dependencies["@/lib/connector-action-route"] = await load(
    "../../web/src/lib/connector-action-route.ts",
  );
  const conversationRoutes =
    "../../web/src/app/api/chat/conversations/[conversationId]";
  const relative =
    kind === "list"
      ? `${conversationRoutes}/connector-actions/route.ts`
      : kind === "conversationGrants"
        ? `${conversationRoutes}/connector-conversation-grants/route.ts`
        : kind === "revokeConversationGrant"
          ? `${conversationRoutes}/connector-conversation-grants/[grantId]/route.ts`
          : `../../web/src/app/api/connector-actions/[actionId]/${kind}/route.ts`;
  const route = (await load(relative)) as {
    GET: (request: Request, context: unknown) => Promise<Response>;
    POST: (request: Request, context: unknown) => Promise<Response>;
    DELETE: (request: Request, context: unknown) => Promise<Response>;
  };
  const request = new Request(
    "https://example.test/action",
    kind === "approve"
      ? { method: "POST", body: JSON.stringify(approveBody) }
      : undefined,
  );
  const handler =
    kind === "list" || kind === "conversationGrants"
      ? route.GET
      : kind === "revokeConversationGrant"
        ? route.DELETE
        : route.POST;
  const response = await handler(request, {
    params: Promise.resolve({ actionId: ID, conversationId: ID, grantId: "7" }),
  });
  return { response, calls };
}

test("Python producer의 표시 이름은 조회·승인·거절 응답에 남고 원문 결과는 버린다", async () => {
  for (const catalog of catalogs) {
    const labels =
      catalog.tools[catalog.id === "gmail" ? "send_message" : "save_draft"]
        .arg_labels;
    for (const kind of ["list", "approve", "reject"] as const) {
      const source = item(labels);
      const { response, calls } = await callRoute(
        kind,
        kind === "list" ? [source] : source,
      );
      assert.equal(response.status, 200);
      const body = await response.json();
      const safe = kind === "list" ? body[0] : body;
      assert.deepEqual(
        safe.argLabels,
        action.visibleApprovalArgLabels(labels, source.argsJson),
      );
      assert.equal(safe.argsJson, source.argsJson);
      assert.equal(safe.hiddenArgs, false);
      assert.equal(safe.grantAllowed, false);
      assert.equal(safe.resultText, null);
      assert.equal(safe.secret, undefined);
      assert.equal(
        calls[0].path,
        kind === "list"
          ? `/api/v1/chat/conversations/${ID}/connector-actions`
          : `/api/v1/connector-actions/${ID}/${kind}`,
      );
      if (kind === "approve")
        assert.equal(
          JSON.stringify(calls[0].init),
          '{"method":"POST","body":{"grant":null}}',
        );
    }
  }
});

test("실제 Java DTO와 가림 전 producer metadata 모두에서 숨긴 원래 키를 응답에 싣지 않는다", async () => {
  const dto = JSON.parse(
    await readFile(
      new URL(
        "../resources/connector-argument-masked-action.json",
        import.meta.url,
      ),
      "utf8",
    ),
  );
  const catalog = JSON.parse(
    await readFile(
      new URL(
        "../resources/connector-argument-masked-catalog.json",
        import.meta.url,
      ),
      "utf8",
    ),
  );
  const originalLabels = catalog[0].tools.write_note.arg_labels;
  const maskedKey = Object.keys(originalLabels).find((key) => key !== "scope")!;
  for (const metadata of [dto.argLabels, originalLabels]) {
    for (const kind of ["list", "approve", "reject"] as const) {
      const source = { ...dto, argLabels: metadata };
      const { response } = await callRoute(
        kind,
        kind === "list" ? [source] : source,
      );
      assert.equal(response.status, 200);
      const body = await response.json();
      const safe = kind === "list" ? body[0] : body;
      assert.equal(JSON.stringify(safe).includes(maskedKey), false);
      assert.deepEqual(safe.argLabels, { scope: "범위" });
      assert.equal(safe.hiddenArgs, true);
      assert.equal(safe.grantAllowed, false);
      assert.equal(safe.argsJson, dto.argsJson);
    }
  }
});

test("옛 응답과 잘못된 표시 이름도 승인 줄을 유지한다", async () => {
  for (const labels of [
    undefined,
    null,
    [],
    1,
    { to: 1 },
    { to: {} },
    { to: "줄\n바꿈" },
    { a: "b", b: "다름" },
    { a: "겹침", b: "겹침" },
    JSON.parse('{"__proto__":"이름"}'),
  ]) {
    const { response } = await callRoute("list", [item(labels)]);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json())[0].argLabels, {});
  }
});

test("대화 허락을 고른 승인만 conversationGrant 를 싣고, 기간과 함께 오거나 boolean 이 아니면 Control Plane 을 부르지 않는다", async () => {
  const granted = await callRoute("approve", item({}), {
    grant: null,
    conversationGrant: true,
  });
  assert.equal(granted.response.status, 200);
  assert.equal(
    JSON.stringify(granted.calls[0].init),
    '{"method":"POST","body":{"grant":null,"conversationGrant":true}}',
  );
  for (const body of [
    { grant: "HOUR", conversationGrant: true },
    { grant: null, conversationGrant: "true" },
  ]) {
    const refused = await callRoute("approve", item({}), body);
    assert.equal(refused.response.status, 400);
    assert.equal(refused.calls.length, 0);
  }
});

test("승인 줄의 conversationGrantAllowed 는 참일 때만 참으로 옮긴다", async () => {
  for (const [value, expected] of [
    [true, true],
    ["true", false],
    [undefined, false],
  ] as const) {
    const { response } = await callRoute("approve", {
      ...item({}),
      conversationGrantAllowed: value,
    });
    assert.equal((await response.json()).conversationGrantAllowed, expected);
  }
});

test("원고 저장 요약은 계약의 칸만 옮기고 일반 줄은 null 이며 모양이 틀리면 고정 오류로 돌린다", async () => {
  const contentDraft = {
    snapshotId: ID,
    draftId: ID,
    revision: 3,
    conversationId: ID,
    status: "SAVING",
    stage: "editor",
    saveClicked: false,
    errorCode: null,
    errorReason: "title_cursor_covered",
    approvedBy: "버릴 칸",
  };
  const { response } = await callRoute("list", [
    { ...item({}), contentDraft },
    item({}),
  ]);
  const [draft, plain] = await response.json();
  const { approvedBy: _dropped, ...expected } = contentDraft;
  assert.deepEqual(draft.contentDraft, expected);
  assert.equal(plain.contentDraft, null);
  for (const broken of [
    { ...contentDraft, status: "DONE" },
    { ...contentDraft, snapshotId: "../x" },
    { ...contentDraft, revision: "3" },
  ]) {
    const failed = await callRoute("list", [
      { ...item({}), contentDraft: broken },
    ]);
    assert.equal(failed.response.status, 502);
  }
});

const FINANCIAL = {
  available: true,
  accountSeq: "7",
  operation: "CREATE",
  symbol: "TESTCO",
  market: "US",
  currency: "USD",
  side: "BUY",
  quantity: null,
  orderAmount: "12.5",
  price: null,
  orderType: "MARKET",
  timeInForce: "DAY",
  orderId: null,
  normalization: [{ field: "orderType", before: null, after: "MARKET" }],
};

test("금융 요약은 계약의 칸만 옮기고 금융 줄이 아니면 null 이다", async () => {
  const { response } = await callRoute("list", [
    {
      ...item({}),
      risk: "FINANCIAL",
      financial: { ...FINANCIAL, extra: "버릴 칸" },
    },
    item({}),
  ]);
  const [financial, plain] = await response.json();
  assert.deepEqual(financial.financial, FINANCIAL);
  assert.equal(plain.financial, null);
});

test("열지 못한 금융 요약은 나머지 칸을 비운 채 옮긴다", async () => {
  const { response } = await callRoute("list", [
    {
      ...item({}),
      risk: "FINANCIAL",
      financial: { ...FINANCIAL, available: false },
    },
  ]);
  const [row] = await response.json();
  assert.equal(row.financial.available, false);
  assert.equal(row.financial.accountSeq, null);
  assert.equal(row.financial.symbol, null);
  assert.deepEqual(row.financial.normalization, []);
});

test("모양이 틀린 금융 요약은 그 줄만 열지 못한 것으로 낮추고 목록과 다른 줄은 그대로 보낸다", async () => {
  const other = { ...item({}), risk: "FINANCIAL", financial: FINANCIAL };
  for (const broken of [
    { accountSeq: "abc" },
    { accountSeq: "0" },
    { accountSeq: "07" },
    { side: "HOLD" },
    { orderType: "STOP" },
    { operation: "MODIFY" },
    { currency: "usd" },
    { quantity: "1e3" },
    { orderAmount: "1".repeat(31) },
    { normalization: "없음" },
  ]) {
    const { response } = await callRoute("list", [
      {
        ...item({}),
        risk: "FINANCIAL",
        financial: { ...FINANCIAL, ...broken },
      },
      other,
    ]);
    const label = JSON.stringify(broken);
    assert.equal(response.status, 200, label);
    const [lowered, kept] = await response.json();
    assert.deepEqual(
      lowered.financial,
      {
        available: false,
        accountSeq: null,
        operation: null,
        symbol: null,
        market: null,
        currency: null,
        side: null,
        quantity: null,
        orderAmount: null,
        price: null,
        orderType: null,
        timeInForce: null,
        orderId: null,
        normalization: [],
      },
      label,
    );
    assert.deepEqual(kept.financial, FINANCIAL, label);
  }
  const { response } = await callRoute("list", [
    { ...item({}), risk: "FINANCIAL", financial: "문자열" },
  ]);
  assert.equal(response.status, 200);
  assert.equal((await response.json())[0].financial.available, false);
});

test("대화 허락 목록은 번호와 이름과 끝나는 시각, 에이전트 이름만 옮기고, 거두기는 그 대화의 경로로 보낸다", async () => {
  const listed = await callRoute("conversationGrants", [
    {
      grantId: 7,
      title: "메모 쓰기",
      expiresAt: "2026-10-13T00:00:00Z",
      toolName: "write_note",
      connectorId: "demo-notes",
    },
    {
      grantId: 8,
      title: "글 불러오기",
      expiresAt: "2026-10-13T00:00:00Z",
      agentName: "글 다듬기",
    },
  ]);
  assert.equal(listed.response.status, 200);
  assert.deepEqual(await listed.response.json(), [
    {
      grantId: 7,
      title: "메모 쓰기",
      expiresAt: "2026-10-13T00:00:00Z",
      agentName: null,
    },
    {
      grantId: 8,
      title: "글 불러오기",
      expiresAt: "2026-10-13T00:00:00Z",
      agentName: "글 다듬기",
    },
  ]);
  assert.equal(
    listed.calls[0].path,
    `/api/v1/chat/conversations/${ID}/connector-conversation-grants`,
  );

  const revoked = await callRoute("revokeConversationGrant", null);
  assert.equal(
    revoked.calls[0].path,
    `/api/v1/chat/conversations/${ID}/connector-conversation-grants/7`,
  );
  assert.equal(JSON.stringify(revoked.calls[0].init), '{"method":"DELETE"}');
});
