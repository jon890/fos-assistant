import { expect, test } from "bun:test";
import { readFile, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { Tossinvest } from "../src/client.ts";
import { prepareTrade } from "../src/prepare-trade.ts";
import {
  financialResult,
  tradingBody,
  UNKNOWN,
  type TradeTool,
} from "../src/trading-body.ts";
import { CURRENT_OPERATIONS, OPERATIONS } from "../src/api-contract.ts";
import { createTossinvestServer } from "../src/server.ts";
import {
  ClaimFixture,
  createTradingFixtureServer,
} from "./fixtures/trading-entry.ts";
import {
  FakeToss,
  credentials,
  accountNo,
  stockInfo,
  sampleOrder,
  withMcp,
  tool,
  json,
} from "./support.ts";

const operations = new Set(OPERATIONS.map((operation) => operation.id));
const account = "9223372036854775807";
const environment = () => ({ ...credentials, TOSSINVEST_ACCOUNT_SEQ: account });
const order = () => ({
  ...sampleOrder("original/α ?"),
  quantity: "10",
  price: "12",
  orderAmount: null,
});
function setup(region = "US") {
  const fake = new FakeToss(operations);
  const stock = stockInfo("ABC", "합성 종목");
  stock.market = region === "US" ? "NYSE" : "KOSPI";
  stock.currency = region === "US" ? "USD" : "KRW";
  const original = { ...order(), symbol: "ABC", currency: stock.currency };
  fake.routes.set(
    "GET /api/v1/accounts",
    () =>
      new Response(
        `{"result":[{"accountSeq":${account},"accountNo":"${accountNo}","accountType":"BROKERAGE"}]}`,
        { headers: { "content-type": "application/json" } },
      ),
  );
  fake.on("GET", "/api/v1/stocks", { result: [stock] });
  fake.on("GET", `/api/v1/orders/${encodeURIComponent(original.orderId)}`, {
    result: original,
  });
  return { fake, original, env: environment(), stock };
}
const model = (name: TradeTool, id = "original/α ?") =>
  name === "create_order"
    ? {
        symbol: "abc",
        side: "BUY",
        orderType: "LIMIT",
        quantity: "002",
        price: "0012.3400",
        confirmHighValueOrder: false,
      }
    : name === "modify_order"
      ? { orderId: id, orderType: "LIMIT", price: "13" }
      : { orderId: id };
const fixtureEntry = resolve(import.meta.dir, "fixtures/trading-entry.ts");

async function generate(destination: string) {
  const child = Bun.spawn(
    [process.execPath, fixtureEntry, "--generate", destination],
    { stdout: "pipe", stderr: "pipe" },
  );
  const root = (await new Response(child.stdout).text()).trim();
  expect(await child.exited).toBe(0);
  expect(root).toContain("fos-toss-trading-");
  return root;
}

for (const name of [
  "create_order",
  "modify_order",
  "cancel_order",
] as TradeTool[]) {
  test(`실제 bundle stdio SDK ${name}: claim 1회, 금융 POST 1회, DELETE 0회`, async () => {
    const { fake, env, original } = setup();
    const claims = new ClaimFixture();
    const root = await generate(fake.url);
    const prepareClient = new Client({ name: "prepare-test", version: "1" });
    const executeClient = new Client({ name: "execute-test", version: "1" });
    try {
      await prepareClient.connect(
        new StdioClientTransport({
          command: process.execPath,
          args: [join(root, "dist/tossinvest-mcp.js"), "--serve", fake.url],
          env,
          stderr: "pipe",
        }),
      );
      expect(
        (await prepareClient.listTools()).tools.every(
          (item) =>
            typeof item.description === "string" &&
            !item.description.includes("undefined"),
        ),
      ).toBe(true);
      const prepared = await tool(prepareClient, "prepare_trade", {
        v: 1,
        tool: name,
        args: model(name),
      });
      expect(prepared.result.isError).toBeUndefined();
      const args = prepared.body.executionArgs as Record<string, any>;
      const summary = prepared.body.summary as Record<string, any>;
      expect(summary.account).toBe(accountNo.slice(-4));
      expect(summary.operation).toBe(
        name === "create_order"
          ? "CREATE"
          : name === "modify_order"
            ? "MODIFY"
            : "CANCEL",
      );
      expect(args.confirmHighValueOrder).toBeUndefined();
      if (name === "create_order") {
        expect(args.clientOrderId).toMatch(/^[0-9a-f-]{36}$/);
        expect(summary.normalization).toEqual([
          { field: "symbol", before: "abc", after: "ABC" },
          { field: "timeInForce", before: null, after: "DAY" },
          { field: "quantity", before: "002", after: "2" },
          { field: "price", before: "0012.3400", after: "12.34" },
        ]);
      } else
        expect(args.expected_order.execution).toEqual({ filledQuantity: "0" });
      const path =
        name === "create_order"
          ? "/api/v1/orders"
          : `/api/v1/orders/${encodeURIComponent(original.orderId)}/${name === "modify_order" ? "modify" : "cancel"}`;
      fake.on("POST", path, {
        result: {
          orderId: "new-official-id",
          ...(name === "create_order"
            ? { clientOrderId: args.clientOrderId }
            : {}),
        },
      });
      await executeClient.connect(
        new StdioClientTransport({
          command: process.execPath,
          args: [join(root, "dist/tossinvest-mcp.js"), "--serve", fake.url],
          env: { ...env, ...claims.authority(name, args) },
          stderr: "pipe",
        }),
      );
      const executed = await tool(executeClient, name, args);
      expect(executed.result.isError).toBeUndefined();
      expect(executed.body.orderId).toBe("new-official-id");
      expect(claims.requests).toHaveLength(1);
      expect(fake.seen("POST", path)).toHaveLength(1);
      expect(fake.seen("DELETE")).toHaveLength(0);
      const received = fake.seen("POST", path)[0]!;
      expect(received.headers.get("x-tossinvest-account")).toBe(account);
      expect(JSON.parse(received.body)).toEqual(
        name === "create_order"
          ? {
              symbol: "ABC",
              side: "BUY",
              orderType: "LIMIT",
              quantity: "2",
              price: "12.34",
              timeInForce: "DAY",
              clientOrderId: args.clientOrderId,
            }
          : name === "modify_order"
            ? { orderType: "LIMIT", price: "13" }
            : {},
      );
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(2);
      expect(fake.seen("GET").length).toBe(name === "create_order" ? 2 : 4);
      const replay = await tool(executeClient, name, args);
      expect(replay.result.isError).toBe(true);
      expect(claims.requests).toHaveLength(1);
      expect(fake.seen("POST", path)).toHaveLength(1);
      await writeFile(
        join(root, "prepared.json"),
        JSON.stringify({
          prepared: prepared.body,
          model: model(name),
          tool: name,
        }),
      );
      // 실제 loader가 SHA를 계산하고 기존 Python strict 소비자가 prepare 표시 값을 읽는다.
      const python = Bun.spawn(
        [
          "python3",
          resolve(
            import.meta.dir,
            "../../../tests/fixtures/toss-trading-contract.py",
          ),
          root,
          process.execPath,
        ],
        { stdout: "pipe", stderr: "pipe" },
      );
      const sha = (await new Response(python.stdout).text()).trim();
      const error = await new Response(python.stderr).text();
      expect(await python.exited, error).toBe(0);
      expect(sha).toMatch(/^[0-9a-f]{64}$/);
      console.info(`실제 Python loader SHA ${name}: ${sha}`);
    } finally {
      await prepareClient.close();
      await executeClient.close();
      await rm(root, { recursive: true, force: true });
      claims.stop();
      fake.stop();
    }
  });
}

test("공식 inline oneOf·본문·선택 clientOrderId·오류 예시를 원문에서 직접 읽는다", async () => {
  const source = JSON.parse(
    await readFile(
      join(import.meta.dir, "fixtures/openapi-1.2.24.json"),
      "utf8",
    ),
  );
  expect(source.info.version).toBe("1.2.24");
  const branches = source.components.schemas.OrderCreateRequest.oneOf;
  expect(branches).toHaveLength(2);
  expect(branches.map((branch: any) => branch.required)).toEqual([
    ["symbol", "side", "orderType", "quantity"],
    ["symbol", "side", "orderType", "orderAmount"],
  ]);
  expect(branches[1].properties.timeInForce).toBeUndefined();
  expect(source.components.schemas.OrderResponse.required).toEqual(["orderId"]);
  expect(
    source.components.schemas.OrderResponse.properties.clientOrderId.type,
  ).toEqual(["string", "null"]);
  expect(
    source.paths["/api/v1/orders/{orderId}/cancel"].post.requestBody.content[
      "application/json"
    ].example,
  ).toEqual({});
  for (const methods of Object.values(source.paths) as any[]) {
    const operation = methods.post;
    if (
      !["createOrder", "modifyOrder", "cancelOrder"].includes(
        operation?.operationId,
      )
    )
      continue;
    for (const [status, raw] of Object.entries(operation.responses) as Array<
      [string, any]
    >) {
      const response = raw.$ref
        ? source.components.responses[raw.$ref.split("/").pop()]
        : raw;
      const content = response.content?.["application/json"];
      const examples = [
        ...Object.values(content?.examples ?? {}).map(
          (example: any) => example.value,
        ),
        ...(content?.example ? [content.example] : []),
      ];
      for (const data of examples) {
        if (!data.error) continue;
        const unknown =
          Number(status) >= 429 ||
          [
            "request-in-progress",
            "already-processing",
            "opposite-pending-order-exists",
          ].includes(data.error.code);
        expect(() =>
          financialResult(operation.operationId, {
            status: Number(status),
            data,
            ok: false,
            empty: false,
            retryAfterMs: 0,
          }),
        ).toThrow(unknown ? UNKNOWN : "TOSSINVEST_INVALID_INPUT");
      }
    }
  }
});

for (const [region, args, valid] of [
  ["US", { price: "0.0001" }, true],
  ["US", { price: "0.00001" }, false],
  ["US", { price: "1.01" }, true],
  ["US", { price: "1.001" }, false],
  ["KR", { price: "1.1" }, false],
  ["KR", { price: "1", timeInForce: "OPG" }, true],
  ["KR", { price: "1", timeInForce: "CLS" }, false],
  [
    "US",
    {
      orderType: "MARKET",
      price: undefined,
      side: "SELL",
      quantity: "0.000001",
    },
    true,
  ],
  [
    "US",
    {
      orderType: "MARKET",
      price: undefined,
      side: "SELL",
      quantity: "0.0000001",
    },
    false,
  ],
  ["US", { orderType: "MARKET", price: undefined, quantity: "0.5" }, false],
  ["US", { timeInForce: "OPG" }, false],
  ["KR", { quantity: "0.5" }, false],
] as const) {
  test(`prepare 시장·가격·수량 경계 ${region} ${JSON.stringify(args)}`, async () => {
    const { fake, env } = setup(region);
    try {
      const input: any = { ...model("create_order"), ...args };
      if (input.price === undefined) delete input.price;
      const prepared = prepareTrade(
        new Tossinvest({ apiBase: fake.url, env }),
        "create_order",
        input,
      );
      if (valid) expect((await prepared).v).toBe(1);
      else await expect(prepared).rejects.toThrow("TOSSINVEST_INVALID_INPUT");
      expect(fake.seen("POST", "/api/v1/orders")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
}

test("금액 분기는 US MARKET DAY이고 본문에 price/timeInForce/scope가 없다", async () => {
  const { fake, env } = setup();
  try {
    const prepared = await prepareTrade(
      new Tossinvest({ apiBase: fake.url, env }),
      "create_order",
      {
        symbol: "ABC",
        side: "BUY",
        orderType: "MARKET",
        orderAmount: "0050.00",
      },
    );
    expect(prepared.summary.orderAmount).toBe("50");
    expect(prepared.summary.quantity).toBeNull();
    expect(
      Object.keys(tradingBody("create_order", prepared.executionArgs)).sort(),
    ).toEqual(["clientOrderId", "orderAmount", "orderType", "side", "symbol"]);
  } finally {
    fake.stop();
  }
});

for (const clientOrderId of [undefined, null, "mismatch"]) {
  test(`CREATE 응답 clientOrderId ${clientOrderId}: 선택 nullable 보존`, async () => {
    const { fake, env } = setup();
    const claims = new ClaimFixture();
    try {
      const prepared = await prepareTrade(
        new Tossinvest({ apiBase: fake.url, env }),
        "create_order",
        model("create_order"),
      );
      Object.assign(
        env,
        claims.authority("create_order", prepared.executionArgs),
      );
      fake.on("POST", "/api/v1/orders", {
        result: {
          orderId: "new",
          ...(clientOrderId !== undefined ? { clientOrderId } : {}),
        },
      });
      await withMcp(
        createTradingFixtureServer({ apiBase: fake.url, env }),
        async (client) => {
          const result = await tool(
            client,
            "create_order",
            prepared.executionArgs,
          );
          expect(Boolean(result.result.isError)).toBe(
            clientOrderId === "mismatch",
          );
          if (clientOrderId === "mismatch")
            expect(result.body).toEqual({ error: { code: UNKNOWN } });
        },
      );
      expect(fake.seen("POST", "/api/v1/orders")).toHaveLength(1);
    } finally {
      claims.stop();
      fake.stop();
    }
  });
}

for (const change of [
  { quantity: 2 },
  { quantity: "0" },
  { quantity: "-1" },
  { quantity: "1e2" },
  { quantity: " 2" },
  { quantity: "1".repeat(31) },
  { quantity: "2", orderAmount: "50" },
  { confirmHighValueOrder: true },
  { clientOrderId: "model-reserved" },
  { market: "NYSE" },
  { currency: "USD" },
  { account_seq: "7" },
  { expected_order: {} },
  { userId: "901" },
  { connectionId: "701" },
  { tool: "create_order" },
]) {
  test(`모델 예약 키·decimal 입력 거절 ${JSON.stringify(change)}: HTTP 0회`, async () => {
    const { fake, env } = setup();
    try {
      await expect(
        prepareTrade(
          new Tossinvest({ apiBase: fake.url, env }),
          "create_order",
          { ...model("create_order"), ...change },
        ),
      ).rejects.toThrow("TOSSINVEST_INVALID_INPUT");
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
}

for (const [region, quantity, valid] of [
  ["KR", undefined, false],
  ["KR", "2", true],
  ["US", "2", false],
  ["US", undefined, true],
] as const) {
  test(`정정 ${region} quantity ${quantity}: 공식 KR 필수·US 금지`, async () => {
    const { fake, env } = setup(region);
    try {
      const args = {
        ...model("modify_order"),
        ...(quantity !== undefined ? { quantity } : {}),
      };
      const pending = prepareTrade(
        new Tossinvest({ apiBase: fake.url, env }),
        "modify_order",
        args,
      );
      if (valid)
        expect((await pending).summary.quantity).toBe(quantity ?? "10");
      else await expect(pending).rejects.toThrow("TOSSINVEST_INVALID_INPUT");
      expect(fake.seen("POST", "/api/v1/orders")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
}

for (const [market, currency] of [
  ["UNKNOWN", "USD"],
  ["NYSE", "KRW"],
  ["KOSPI", "USD"],
]) {
  test(`StockInfo 시장/통화 불일치 ${market}/${currency}: 금융 0회`, async () => {
    const { fake, env, stock } = setup();
    fake.on("GET", "/api/v1/stocks", {
      result: [{ ...stock, market, currency }],
    });
    try {
      await expect(
        prepareTrade(
          new Tossinvest({ apiBase: fake.url, env }),
          "create_order",
          model("create_order"),
        ),
      ).rejects.toThrow("TOSSINVEST_INVALID_INPUT");
      expect(fake.seen("POST", "/api/v1/orders")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
}

// 소수점은 미국 MARKET 금액 주문으로만 열고, USD 가격은 받은 decimal 그대로 둔다.
const fractional = (args: Record<string, string>) => ({
  symbol: "ABC",
  timeInForce: "DAY",
  ...args,
});
for (const [label, args, field, expected, normalization] of [
  [
    "미국 MARKET 매도 소수 수량",
    { side: "SELL", orderType: "MARKET", quantity: "0.1" },
    "quantity",
    "0.1",
    [],
  ],
  [
    "미국 LIMIT 매수 USD 가격",
    { side: "BUY", orderType: "LIMIT", quantity: "1", price: "12.34" },
    "price",
    "12.34",
    [],
  ],
  [
    "미국 LIMIT 매수 1달러 미만 USD 가격",
    { side: "BUY", orderType: "LIMIT", quantity: "1", price: "0.1234" },
    "price",
    "0.1234",
    [],
  ],
  [
    "미국 MARKET 매수 금액",
    { side: "BUY", orderType: "MARKET", orderAmount: "25.50" },
    "orderAmount",
    "25.5",
    [{ field: "orderAmount", before: "25.50", after: "25.5" }],
  ],
] as const) {
  test(`소수점·USD 준비 ${label}: SDK와 운영 서버가 같은 값을 내고 금융 POST 0회`, async () => {
    const { fake, env } = setup("US");
    try {
      const direct = await prepareTrade(
        new Tossinvest({ apiBase: fake.url, env }),
        "create_order",
        fractional(args),
      );
      let served: any;
      await withMcp(
        createTossinvestServer({ apiBase: fake.url, env: { ...env } }),
        async (client) => {
          const response = await tool(client, "prepare_trade", {
            v: 1,
            tool: "create_order",
            args: fractional(args),
          });
          expect(response.result.isError).toBeUndefined();
          served = response.body;
        },
      );
      for (const prepared of [direct, served]) {
        expect(prepared.executionArgs[field]).toBe(expected);
        expect(prepared.executionArgs.symbol).toBe("ABC");
        expect(prepared.summary.normalization).toEqual(normalization);
        if (field === "orderAmount") {
          expect(prepared.summary.orderAmount).toBe(expected);
          expect(prepared.summary.price).toBeNull();
          expect(prepared.summary.quantity).toBeNull();
        } else expect(prepared.summary[field]).toBe(expected);
      }
      expect(fake.seen("POST", "/api/v1/orders")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
}

for (const [label, region, args] of [
  [
    "미국 LIMIT 매수 소수 수량",
    "US",
    { side: "BUY", orderType: "LIMIT", quantity: "0.1", price: "12.34" },
  ],
  [
    "국내 금액 주문",
    "KR",
    { side: "BUY", orderType: "MARKET", orderAmount: "10000" },
  ],
  [
    "LIMIT 금액 주문",
    "US",
    { side: "BUY", orderType: "LIMIT", orderAmount: "25", price: "12.34" },
  ],
] as const) {
  test(`소수점·금액 준비 거절 ${label}: SDK와 운영 서버 모두 금융 POST 0회`, async () => {
    const { fake, env } = setup(region);
    try {
      await expect(
        prepareTrade(
          new Tossinvest({ apiBase: fake.url, env }),
          "create_order",
          fractional(args),
        ),
      ).rejects.toThrow("TOSSINVEST_INVALID_INPUT");
      await withMcp(
        createTossinvestServer({ apiBase: fake.url, env: { ...env } }),
        async (client) => {
          const response = await tool(client, "prepare_trade", {
            v: 1,
            tool: "create_order",
            args: fractional(args),
          });
          expect(response.result.isError).toBe(true);
          expect(response.body).toEqual({
            error: { code: "TOSSINVEST_INVALID_INPUT" },
          });
        },
      );
      expect(fake.seen("POST", "/api/v1/orders")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });
}

test("운영 서버의 prepare_trade는 정정을 받지 않고 HTTP 0회다", async () => {
  const { fake, env } = setup("US");
  try {
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env: { ...env } }),
      async (client) => {
        const response = await client.callTool({
          name: "prepare_trade",
          arguments: { v: 1, tool: "modify_order", args: model("modify_order") },
        });
        expect(response.isError).toBe(true);
        expect(
          (await client.listTools()).tools.map((item) => item.name),
        ).not.toContain("modify_order");
      },
    );
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});
