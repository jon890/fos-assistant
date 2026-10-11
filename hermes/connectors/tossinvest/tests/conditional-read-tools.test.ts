import { expect, test } from "bun:test";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import canonical from "./fixtures/openapi-1.2.24.json";
import { Tossinvest } from "../src/client.ts";
import { registerConditionalReadTools } from "../src/conditional-read-tools.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import {
  FakeToss,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";

const path = "/api/v1/conditional-orders";
const account = "9223372036854775807";
const detail =
  canonical.paths["/api/v1/conditional-orders/{conditionalOrderId}"].get
    .responses["200"].content["application/json"].example;
const page =
  canonical.paths["/api/v1/conditional-orders"].get.responses["200"].content[
    "application/json"
  ].example;
const officialId = detail.result.conditionalOrderId;

function setup(timeoutMs = 4000) {
  const fake = new FakeToss(
    new Set([
      "issueOAuth2Token",
      "getConditionalOrders",
      "getConditionalOrder",
    ]),
  );
  const server = new McpServer({ name: "conditional-read-test", version: "1" });
  const upstream = new Tossinvest({
    apiBase: fake.url,
    timeoutMs,
    env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: account },
  });
  registerConditionalReadTools(createToolRegistration(server), upstream);
  return { fake, server };
}

async function run(
  context: ReturnType<typeof setup>,
  work: (client: Client) => Promise<void>,
) {
  try {
    await withMcp(context.server, work);
  } finally {
    context.fake.stop();
    // 인증 POST는 허용하지만 금융 쓰기는 한 번도 보내지 않는다.
    expect(
      context.fake.requests.filter(
        (request) =>
          request.method === "DELETE" ||
          (request.method === "POST" && request.path !== "/oauth2/token"),
      ),
    ).toHaveLength(0);
  }
}

test.each(["OPEN", "CLOSED"])(
  "%s 기본 20건과 공식 두 성공 예시의 GET·계좌·query·빈 body를 확인한다",
  async (status) => {
    const context = setup();
    const { fake } = context;
    fake.on("GET", path, page);
    fake.on("GET", `${path}/${officialId}`, detail);
    await run(context, async (client) => {
      const listing = await client.listTools();
      expect(listing.tools.map((entry) => entry.name).sort()).toEqual([
        "get_conditional_order",
        "list_conditional_orders",
      ]);
      for (const entry of listing.tools)
        expect(entry.annotations?.readOnlyHint).toBe(true);
      const list = await tool(client, "list_conditional_orders", { status });
      expect(list.result.isError).toBeUndefined();
      expect(list.body).toEqual({
        result: page.result,
        metadata: {},
        ...page.result,
      });
      const single = await tool(client, "get_conditional_order", {
        conditionalOrderId: officialId,
      });
      expect(single.result.isError).toBeUndefined();
      expect(single.body).toEqual({ result: detail.result, metadata: {} });
    });
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(fake.seen("GET")).toHaveLength(2);
    expect(fake.requests).toHaveLength(3);
    for (const request of fake.seen("GET")) {
      expect(request.headers.get("x-tossinvest-account")).toBe(account);
      expect(request.headers.get("authorization")).toBe(
        "Bearer fake-access-token-1",
      );
      expect(request.body).toBe("");
    }
    expect(Object.fromEntries(fake.seen("GET", path)[0]!.query)).toEqual({
      status,
      limit: "20",
    });
    expect(fake.seen("GET", `${path}/${officialId}`)[0]!.query.size).toBe(0);
  },
);

const decimal = "0.987654321098765432109876543210";
function row(type: string, condition: string, status = "WATCHING") {
  return {
    ...detail.result,
    conditionalOrderId: `synthetic-${type}-${condition}`,
    type,
    status,
    quantity: decimal,
    first: {
      ...detail.result.first,
      type: condition,
      status: "ORDERED",
      triggerPrice: condition === "PROFIT_RATE" ? null : decimal,
      targetProfitRate:
        condition === "PROFIT_RATE" ? "-10.500000000000001" : null,
      orderPrice: null,
      triggeredOrderId: "synthetic-triggered-order",
    },
    second:
      type === "SINGLE"
        ? null
        : {
            ...detail.result.second,
            type: condition,
            status: type === "OTO" ? "HOLDING" : "CANCELED",
            triggerPrice: null,
            targetProfitRate: condition === "PROFIT_RATE" ? decimal : null,
            triggeredOrderId: null,
          },
  };
}

test.each(["OPEN", "CLOSED"])(
  "%s의 여러 페이지·마지막 null과 모든 주문/조건 타입을 보존한다",
  async (status) => {
    const context = setup();
    const orders = [
      row("SINGLE", "STOP", status === "CLOSED" ? "EXPIRED" : "PAUSED"),
      row("OCO", "PROFIT_RATE", status === "CLOSED" ? "COMPLETED" : "ORDERING"),
      row("OTO", "STOP", status === "CLOSED" ? "EXPIRED" : "ORDERED"),
    ];
    context.fake.sequence(
      "GET",
      path,
      orders.map(
        (order, index) => () =>
          json({
            result: {
              conditionalOrders: [order],
              nextCursor: index === 2 ? null : `page_${index + 1}-next`,
              hasNext: index !== 2,
            },
          }),
      ),
    );
    await run(context, async (client) => {
      let cursor: string | undefined;
      for (let index = 0; index < 3; index++) {
        const { body } = await tool(client, "list_conditional_orders", {
          status,
          symbol: "BRK.B",
          limit: 100,
          ...(cursor ? { cursor } : {}),
        });
        expect(body.conditionalOrders).toEqual([orders[index]]);
        expect(body.result).toEqual({
          conditionalOrders: [orders[index]],
          nextCursor: index === 2 ? null : `page_${index + 1}-next`,
          hasNext: index !== 2,
        });
        cursor = body.nextCursor as string | undefined;
        expect(body.hasNext).toBe(index !== 2);
      }
    });
    const requests = context.fake.seen("GET", path);
    expect(requests).toHaveLength(3);
    expect(
      requests.map((request) => Object.fromEntries(request.query)),
    ).toEqual([
      { status, symbol: "BRK.B", limit: "100" },
      { status, symbol: "BRK.B", limit: "100", cursor: "page_1-next" },
      { status, symbol: "BRK.B", limit: "100", cursor: "page_2-next" },
    ]);
  },
);

test.each(["STOP", "PROFIT_RATE"])(
  "%s 상세의 expireDate 부재와 중첩 값을 보존한다",
  async (condition) => {
    const context = setup();
    const data = row("OTO", condition);
    const { expireDate: _unused, ...withoutExpiry } = data;
    context.fake.on("GET", `${path}/${officialId}`, { result: withoutExpiry });
    await run(context, async (client) => {
      const { body } = await tool(client, "get_conditional_order", {
        conditionalOrderId: officialId,
      });
      expect(body.result).toEqual(withoutExpiry);
      expect(Object.hasOwn(body.result as object, "expireDate")).toBe(false);
      expect((body.result as any).status).toBe("WATCHING");
      expect((body.result as any).first.status).toBe("ORDERED");
      expect((body.result as any).second.status).toBe("HOLDING");
    });
  },
);

test("긴 ID와 Unicode를 한 segment로 인코딩하고 불필요한 상한을 두지 않는다", async () => {
  const context = setup();
  const id = `synthetic-${"x".repeat(4096)}-가`;
  const encoded = encodeURIComponent(id);
  context.fake.on("GET", `${path}/${encoded}`, detail);
  await run(context, async (client) => {
    const response = await tool(client, "get_conditional_order", {
      conditionalOrderId: id,
    });
    expect(response.result.isError).toBeUndefined();
  });
  expect(context.fake.seen("GET")[0]!.path).toBe(`${path}/${encoded}`);
});

test.each([
  {},
  { status: "WATCHING" },
  { status: " OPEN" },
  { status: "OPEN", symbol: "AAPL&account=2" },
  { status: "OPEN", symbol: "" },
  { status: "OPEN", cursor: "" },
  { status: "OPEN", cursor: "next?status=CLOSED" },
  { status: "OPEN", cursor: "../x" },
  { status: "OPEN", cursor: "x%26y" },
  { status: "OPEN", limit: 0 },
  { status: "CLOSED", limit: 101 },
  { status: "OPEN", limit: 1.5 },
])("잘못된 목록 입력 %j는 인증과 HTTP 전에 거절한다", async (input) => {
  const context = setup();
  await run(context, (client) =>
    expectFailure(
      client,
      "TOSSINVEST_INVALID_INPUT",
      "list_conditional_orders",
      input,
    ),
  );
  expect(context.fake.requests).toHaveLength(0);
});

test("C0·DEL·C1 전체는 인증을 포함해 HTTP 전에 입력 오류로 거절한다", async () => {
  const context = setup();
  await run(context, async (client) => {
    for (const code of [
      ...Array.from({ length: 32 }, (_, index) => index),
      ...Array.from({ length: 33 }, (_, index) => 127 + index),
    ]) {
      await expectFailure(
        client,
        "TOSSINVEST_INVALID_INPUT",
        "get_conditional_order",
        {
          conditionalOrderId: `a${String.fromCharCode(code)}b`,
        },
      );
      expect(context.fake.requests).toHaveLength(0);
    }
    await expectFailure(
      client,
      "TOSSINVEST_INVALID_INPUT",
      "get_conditional_order",
      {},
    );
    expect(context.fake.requests).toHaveLength(0);
  });
});

test.each([
  "",
  ".",
  "..",
  "../x",
  "x/y",
  "x\\y",
  "x?account=2",
  "x#fragment",
  "%2e%2e",
  "%252f",
  "https://evil.example/x",
  "x\n",
  " x",
  "\ud800",
])("잘못된 ID %j는 인증과 HTTP 전에 거절한다", async (conditionalOrderId) => {
  const context = setup();
  await run(context, (client) =>
    expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_conditional_order", {
      conditionalOrderId,
    }),
  );
  expect(context.fake.requests).toHaveLength(0);
});

test.each([
  ["list_conditional_orders", { status: "OPEN", account: "other" }],
  ["list_conditional_orders", { status: "OPEN", url: "https://evil.example" }],
  ["list_conditional_orders", { status: "OPEN", token: "other" }],
  ["list_conditional_orders", { status: "OPEN", limit: "20" }],
  [
    "get_conditional_order",
    { conditionalOrderId: officialId, account: "other" },
  ],
  ["get_conditional_order", { conditionalOrderId: 123 }],
])(
  "%s의 추가 키와 잘못된 타입 %j는 SDK가 거절하며 HTTP는 없다",
  async (name, input) => {
    const context = setup();
    await run(context, async (client) => {
      const result = await client.callTool({
        name: name as string,
        arguments: input as Record<string, unknown>,
      });
      expect(result.isError).toBe(true);
    });
    expect(context.fake.requests).toHaveLength(0);
  },
);

test.each([
  ["list_conditional_orders", path, { status: "OPEN" }],
  [
    "get_conditional_order",
    `${path}/${officialId}`,
    { conditionalOrderId: officialId },
  ],
])(
  "%s의 429를 한 번 재시도하고 5xx·malformed·크기 초과를 전체 오류로 반환한다",
  async (name, route, input) => {
    const context = setup();
    const success = name === "list_conditional_orders" ? page : detail;
    context.fake.sequence("GET", route as string, [
      () =>
        json(
          {
            error: {
              requestId: "synthetic",
              code: "rate-limit",
              message: "synthetic",
            },
          },
          429,
        ),
      () => json(success),
      () =>
        json(
          {
            error: {
              requestId: "synthetic",
              code: "failure",
              message: "synthetic",
            },
          },
          503,
        ),
      () => new Response("{broken"),
      () => new Response("x".repeat(1024 * 1024 + 1)),
    ]);
    await run(context, async (client) => {
      expect(
        (await tool(client, name as string, input as Record<string, unknown>))
          .result.isError,
      ).toBeUndefined();
      for (let index = 0; index < 3; index++)
        await expectFailure(
          client,
          "TOSSINVEST_UNAVAILABLE",
          name as string,
          input as Record<string, unknown>,
        );
    });
    expect(context.fake.seen("GET", route as string)).toHaveLength(5);
    expect(context.fake.seen("POST", "/oauth2/token")).toHaveLength(1);
  },
);

test("잘못된 nested 행을 버리지 않고 목록 전체를 거절한다", async () => {
  const context = setup();
  context.fake.on("GET", path, {
    result: {
      conditionalOrders: [
        detail.result,
        {
          ...detail.result,
          first: { ...detail.result.first, targetProfitRate: 10.5 },
        },
      ],
      nextCursor: null,
      hasNext: false,
    },
  });
  await run(context, (client) =>
    expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_conditional_orders", {
      status: "OPEN",
    }),
  );
  expect(context.fake.seen("GET", path)).toHaveLength(1);
});

test("상세의 잘못된 중첩 타입과 필수 필드 누락을 전체 오류로 거절한다", async () => {
  const context = setup();
  const { first: _unused, ...missingFirst } = detail.result;
  context.fake.sequence("GET", `${path}/${officialId}`, [
    () => json({ result: { ...detail.result, second: [] } }),
    () => json({ result: missingFirst }),
  ]);
  await run(context, async (client) => {
    for (let index = 0; index < 2; index++)
      await expectFailure(
        client,
        "TOSSINVEST_UNAVAILABLE",
        "get_conditional_order",
        { conditionalOrderId: officialId },
      );
  });
  expect(context.fake.seen("GET", `${path}/${officialId}`)).toHaveLength(2);
});

test("최소 limit 1과 빈 페이지, 선택 필드의 부재를 보존한다", async () => {
  const context = setup();
  const empty = { conditionalOrders: [], hasNext: false };
  context.fake.on("GET", path, { result: empty });
  await run(context, async (client) => {
    const { body } = await tool(client, "list_conditional_orders", {
      status: "OPEN",
      limit: 1,
    });
    expect(body).toEqual({ result: empty, metadata: {}, ...empty });
    expect(Object.hasOwn(body, "nextCursor")).toBe(false);
  });
  expect(context.fake.seen("GET", path)[0]!.query.get("limit")).toBe("1");
});

test.each([
  ["list_conditional_orders", path, { status: "OPEN" }],
  [
    "get_conditional_order",
    `${path}/${officialId}`,
    { conditionalOrderId: officialId },
  ],
])(
  "%s의 HTTP timeout은 NETWORK이고 재전송하지 않는다",
  async (name, route, input) => {
    const context = setup(150);
    context.fake.routes.set(`GET ${route}`, async () => {
      await Bun.sleep(300);
      return json(name === "list_conditional_orders" ? page : detail);
    });
    await run(context, (client) =>
      expectFailure(
        client,
        "TOSSINVEST_NETWORK",
        name as string,
        input as Record<string, unknown>,
      ),
    );
    expect(context.fake.seen("GET", route as string)).toHaveLength(1);
  },
);

test("목록과 상세가 조회 큐와 deadline을 공유해 대기 중인 상세 HTTP를 보내지 않는다", async () => {
  const context = setup(100);
  context.fake.on("GET", path, page);
  context.fake.on("GET", `${path}/${officialId}`, detail);
  await run(context, async (client) => {
    expect(
      (await tool(client, "list_conditional_orders", { status: "OPEN" })).result
        .isError,
    ).toBeUndefined();
    await expectFailure(client, "TOSSINVEST_NETWORK", "get_conditional_order", {
      conditionalOrderId: officialId,
    });
  });
  expect(context.fake.seen("GET", path)).toHaveLength(1);
  expect(context.fake.seen("GET", `${path}/${officialId}`)).toHaveLength(0);
});
