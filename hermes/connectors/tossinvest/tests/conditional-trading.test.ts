import { expect, setSystemTime, test } from "bun:test";
import { readFile, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { Tossinvest } from "../src/client.ts";
import { OPERATIONS } from "../src/api-contract.ts";
import { createTossinvestServer } from "../src/server.ts";
import { prepareConditionalTrade } from "../src/prepare-conditional.ts";
import {
  FakeToss,
  credentials,
  stockInfo,
  tool,
  withMcp,
  useSkippedWaits,
} from "./support.ts";
import {
  ACCOUNT,
  PATHS,
  models,
  setup,
  type ConditionalTool,
} from "./conditional-support.ts";
import { ClaimFixture } from "./fixtures/conditional-entry.ts";

useSkippedWaits();

const entry = resolve(import.meta.dir, "fixtures/conditional-entry.ts");
const contract = resolve(
  import.meta.dir,
  "../../../tests/fixtures/toss-conditional-contract.py",
);

async function run(command: string[]) {
  const child = Bun.spawn(command, { stdout: "pipe", stderr: "pipe" });
  const [out, err, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ]);
  expect(code, err).toBe(0);
  return out.trim();
}

async function connect(root: string, destination: string, env: object) {
  const client = new Client({ name: "conditional-test", version: "1" });
  await client.connect(
    new StdioClientTransport({
      command: process.execPath,
      args: [join(root, "dist/tossinvest-mcp.js"), "--serve", destination],
      env: env as Record<string, string>,
      stderr: "pipe",
    }),
  );
  return client;
}

const expectedBodies = (args: any) => ({
  create_conditional_order: {
    symbol: "ABC",
    type: "OCO",
    quantity: "2",
    orderType: "LIMIT",
    clientOrderId: args.clientOrderId,
    expireDate: args.expireDate,
    first: { orderSide: "SELL", triggerPrice: "110", orderPrice: "110" },
    second: { orderSide: "SELL", triggerPrice: "90", orderPrice: "90" },
    confirmHighValueOrder: false,
  },
  modify_conditional_order: {
    type: "SINGLE",
    quantity: "1",
    orderType: "MARKET",
    expireDate: args.expireDate,
    first: { orderSide: "SELL", triggerPrice: "95" },
    confirmHighValueOrder: false,
  },
});

for (const name of Object.keys(PATHS) as ConditionalTool[]) {
  test(`실제 bundle stdio SDK ${name}: Python 소비, claim 1회, 목적지 단일 전송`, async () => {
    const { fake, env } = setup(name);
    const claims = new ClaimFixture();
    const root = await run([process.execPath, entry, "--generate", fake.url]);
    expect(root).toContain("fos-toss-conditional-");
    const prepare = await connect(root, fake.url, env);
    let execute: Client | undefined;
    try {
      const listed = (await prepare.listTools()).tools.map((item) => item.name);
      expect(listed).toContain("prepare_conditional_trade");
      expect(listed).toContain(name);
      const model = models()[name];
      const prepared = await tool(prepare, "prepare_conditional_trade", {
        v: 1,
        tool: name,
        args: model,
      });
      expect(prepared.result.isError).toBeUndefined();
      const args = prepared.body.executionArgs as any;
      const summary = prepared.body.summary as any;
      expect(summary.orderKind).toBe("CONDITIONAL");
      expect(summary.account).toBe("1234");
      expect(args.confirmHighValueOrder).toBeUndefined();
      expect(fake.seen("GET", "/api/v1/prices")).toHaveLength(
        name === "create_conditional_order" ? 1 : 0,
      );
      // 실제 loader 가 manifest SHA 를 계산하고 실제 _prepare_result 가 조건 분기로 소비한다.
      await writeFile(
        join(root, "prepared.json"),
        JSON.stringify({ prepared: prepared.body, model, tool: name }),
      );
      const sha = await run(["python3", contract, root, process.execPath]);
      expect(sha).toMatch(/^[0-9a-f]{64}$/);

      const [method, path] = PATHS[name];
      if (name === "cancel_conditional_order")
        fake.routes.set(
          `${method} ${path}`,
          new Response(null, { status: 204 }),
        );
      else
        fake.on(method, path, {
          result: {
            conditionalOrderId: "cond-new",
            ...(name === "create_conditional_order"
              ? { clientOrderId: args.clientOrderId }
              : {}),
          },
        });
      execute = await connect(root, fake.url, {
        ...env,
        ...claims.authority(name, args),
      });
      const start = fake.requests.length;
      const executed = await tool(execute, name, args);
      expect(executed.result.isError).toBeUndefined();
      expect(executed.body).toEqual(
        name === "cancel_conditional_order"
          ? { conditionalOrderId: "cond-1", canceled: true }
          : name === "create_conditional_order"
            ? {
                conditionalOrderId: "cond-new",
                clientOrderId: args.clientOrderId,
              }
            : { conditionalOrderId: "cond-new" },
      );
      expect(claims.requests).toHaveLength(1);
      expect(
        fake.requests.slice(start).map((r) => `${r.method} ${r.path}`),
      ).toEqual([
        "POST /oauth2/token",
        ...(name === "create_conditional_order"
          ? ["GET /api/v1/prices"]
          : ["GET /api/v1/conditional-orders/cond-1"]),
        `${method} ${path}`,
      ]);
      const posts = fake.requests.filter(
        (r) => r.method === "POST" && r.path.startsWith("/api/v1/"),
      );
      expect([posts.length, fake.seen("DELETE").length]).toEqual(
        name === "cancel_conditional_order" ? [0, 1] : [1, 0],
      );
      const received = fake.seen(method, path)[0]!;
      expect(received.headers.get("x-tossinvest-account")).toBe(ACCOUNT);
      if (name === "cancel_conditional_order") expect(received.body).toBe("");
      else {
        const body = JSON.parse(received.body);
        expect(body).toEqual(expectedBodies(args)[name]);
        for (const key of [
          "account_seq",
          "market",
          "currency",
          "expected_conditional_order",
          "scope",
          ...(name === "modify_conditional_order"
            ? ["symbol", "clientOrderId", "conditionalOrderId"]
            : []),
        ])
          expect(Object.hasOwn(body, key)).toBe(false);
      }
      const end = fake.requests.length;
      expect((await tool(execute, name, args)).result.isError).toBe(true);
      expect(fake.requests).toHaveLength(end);
      expect(claims.requests).toHaveLength(1);
      console.info(
        `조건 ${name}: Python SHA ${sha.slice(0, 12)} claim=1 금융 ${method}=1 replayHTTP=0`,
      );
    } finally {
      await prepare.close();
      await execute?.close();
      claims.stop();
      fake.stop();
      await rm(root, { recursive: true, force: true });
    }
  });
}

test("공식 fixture 의 세 operation method·path·본문·응답 schema 를 원문에서 직접 읽는다", async () => {
  const source = JSON.parse(
    await readFile(
      join(import.meta.dir, "fixtures/openapi-1.2.24.json"),
      "utf8",
    ),
  );
  const ref = (value: any) => value?.$ref?.split("/").pop();
  const create = source.paths["/api/v1/conditional-orders"].post;
  const modify =
    source.paths["/api/v1/conditional-orders/{conditionalOrderId}/modify"].post;
  const cancel =
    source.paths["/api/v1/conditional-orders/{conditionalOrderId}"].delete;
  expect([create, modify, cancel].map((item) => item.operationId)).toEqual([
    "createConditionalOrder",
    "modifyConditionalOrder",
    "cancelConditionalOrder",
  ]);
  expect(ref(create.requestBody.content["application/json"].schema)).toBe(
    "ConditionalOrderCreateRequest",
  );
  expect(ref(modify.requestBody.content["application/json"].schema)).toBe(
    "ConditionalOrderModifyRequest",
  );
  expect(cancel.requestBody).toBeUndefined();
  expect(Object.keys(cancel.responses)).toContain("204");
  expect(cancel.responses["204"].content).toBeUndefined();
  const result = (operation: any) =>
    ref(
      operation.responses["200"].content["application/json"].schema.allOf[1]
        .properties.result,
    );
  expect([result(create), result(modify)]).toEqual([
    "ConditionalOrderCreateResponse",
    "ConditionalOrderResponse",
  ]);
  const schemas = source.components.schemas;
  expect(schemas.ConditionalOrderCreateResponse.required).toEqual([
    "conditionalOrderId",
  ]);
  expect(
    schemas.ConditionalOrderCreateResponse.properties.clientOrderId.type,
  ).toEqual(["string", "null"]);
  expect(
    schemas.ConditionalOrderModifyRequest.properties.symbol,
  ).toBeUndefined();
  expect(
    schemas.ConditionalOrderModifyRequest.properties.clientOrderId,
  ).toBeUndefined();
  expect(schemas.ConditionalRequest ?? schemas.ConditionRequest).toBeDefined();
  expect(Object.keys(schemas.ConditionRequest.properties).sort()).toEqual([
    "orderPrice",
    "orderSide",
    "triggerPrice",
  ]);
});

test("prepare 결과가 공통 원문 vector 와 같은 UTF-8 원문이다", async () => {
  const vectors = JSON.parse(
    await readFile(
      resolve(
        import.meta.dir,
        "../../../../test/resources/conditional-snapshot-v1.json",
      ),
      "utf8",
    ),
  ).cases;
  // vector 의 만료일이 실행 날짜 검사를 지나도록 공식 확인일로 시계를 옮긴다.
  setSystemTime(new Date("2026-10-11T03:00:00Z"));
  try {
    for (const label of ["KEY-AAPL", "KEY-MODIFY", "KEY-CANCEL"]) {
      const vector = vectors.find((item: any) => item.name === label);
      const name = vector.tool as ConditionalTool;
      const fake = new FakeToss(new Set(OPERATIONS.map((item) => item.id)));
      try {
        fake.routes.set(
          "GET /api/v1/accounts",
          () =>
            new Response(
              '{"result":[{"accountSeq":1234,"accountNo":"99991234","accountType":"BROKERAGE"}]}',
              { headers: { "content-type": "application/json" } },
            ),
        );
        fake.on("GET", "/api/v1/stocks", {
          result: [stockInfo("AAPL", "합성")],
        });
        if (name !== "create_conditional_order")
          fake.on("GET", "/api/v1/conditional-orders/cond-1", {
            result: JSON.parse(vector.executionArgsJson)
              .expected_conditional_order,
          });
        const prepared = await prepareConditionalTrade(
          new Tossinvest({
            apiBase: fake.url,
            env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "1234" },
          }),
          name,
          JSON.parse(vector.modelArgsJson),
        );
        if (prepared.executionArgs.clientOrderId)
          prepared.executionArgs.clientOrderId =
            "00000000-0000-4000-8000-000000000001";
        expect(JSON.stringify(prepared.executionArgs)).toBe(
          vector.executionArgsJson,
        );
        expect(JSON.stringify(prepared.summary)).toBe(vector.summaryJson);
        expect(
          fake.requests.filter(
            (r) => r.method !== "GET" && r.path !== "/oauth2/token",
          ),
        ).toHaveLength(0);
      } finally {
        fake.stop();
      }
    }
  } finally {
    setSystemTime();
  }
});

test("운영 SDK 는 조건주문 준비·쓰기 도구를 등록하지 않고 금융 HTTP 0회다", async () => {
  const { fake, env } = setup("create_conditional_order");
  try {
    const manifest = JSON.parse(
      await readFile(resolve(import.meta.dir, "../assistant.json"), "utf8"),
    );
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env }),
      async (sdk) => {
        const names = (await sdk.listTools()).tools
          .map((item) => item.name)
          .sort();
        expect(names).toEqual(Object.keys(manifest.tools).sort());
        for (const name of ["prepare_conditional_trade", ...Object.keys(PATHS)])
          expect(names).not.toContain(name);
        const call = await sdk.callTool({
          name: "create_conditional_order",
          arguments: {},
        });
        expect(call.isError).toBe(true);
      },
    );
    expect(Object.keys(manifest.execution_guard.operations).sort()).toEqual([
      "cancel_order",
      "create_order",
    ]);
    expect(manifest.execution_guard.kinds).toBeUndefined();
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});
