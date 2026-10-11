import { describe, expect, test } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Tossinvest } from "../src/client.ts";
import { registerOrderReadTools } from "../src/order-read-tools.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import fixture from "./fixtures/openapi-1.2.24.json";
import { FakeToss, credentials, sampleOrder, withMcp } from "./support.ts";

const sentinel = "synthetic-sensitive-extension";
const accountSeq = "7";

async function run(
  work: (
    fake: FakeToss,
    client: Parameters<Parameters<typeof withMcp>[1]>[0],
  ) => Promise<void>,
) {
  const fake = new FakeToss(
    new Set(["issueOAuth2Token", "getOrder", "getCommissions"]),
  );
  const server = new McpServer({ name: "order-read-test", version: "1.0.0" });
  registerOrderReadTools(
    createToolRegistration(server),
    new Tossinvest({
      apiBase: fake.url,
      env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: accountSeq },
    }),
  );
  try {
    await withMcp(server, (client) => work(fake, client));
  } finally {
    try {
      expect(fake.seen("POST", "/oauth2/token").length).toBe(fake.issued);
      expect(
        fake.requests.filter(
          (r) => r.method === "POST" && r.path !== "/oauth2/token",
        ),
      ).toHaveLength(0);
      expect(fake.seen("DELETE")).toHaveLength(0);
    } finally {
      fake.stop();
    }
  }
}

/** 모든 객체와 배열에 합성 확장키를 넣고 공식 scalar는 유지한다. */
function contaminated(value: any): any {
  if (Array.isArray(value))
    return Object.assign(value.map(contaminated), {
      token: sentinel,
      accountNo: sentinel,
    });
  if (value && typeof value === "object")
    return {
      ...Object.fromEntries(
        Object.entries(value).map(([key, child]) => [key, contaminated(child)]),
      ),
      accountNo: sentinel,
      token: sentinel,
      metadata: { token: sentinel, nested: [{ accountNo: sentinel }] },
    };
  return value;
}

function success(result: any, expected: unknown) {
  expect(result.isError).not.toBe(true);
  expect(result.structuredContent).toEqual({ result: expected, metadata: {} });
  expect(JSON.parse(result.content[0].text)).toEqual(result.structuredContent);
  for (const forbidden of [sentinel, "accountNo", '"token"'])
    expect(JSON.stringify(result)).not.toContain(forbidden);
}

function request(fake: FakeToss, path: string) {
  const rows = fake.seen("GET", path);
  expect(rows).toHaveLength(1);
  const row = rows[0]!;
  expect(row.query.toString()).toBe("");
  expect(row.body).toBe("");
  expect(row.headers.get("x-tossinvest-account")).toBe(accountSeq);
  expect(row.headers.get("authorization")).toBe("Bearer fake-access-token-1");
  expect(row.headers.get("accept")).toBe("application/json");
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
}

const examples = (path: string): Record<string, { value: any }> =>
  (fixture.paths as any)[path].get.responses["200"].content["application/json"]
    .examples;

describe("주문 상세와 수수료 독립 조회", () => {
  for (const [name, example] of Object.entries(
    examples("/api/v1/orders/{orderId}"),
  )) {
    test(`공식 주문 ${name}와 확장키 제거`, async () =>
      run(async (fake, client) => {
        const order = example.value.result;
        const path = `/api/v1/orders/${order.orderId}`;
        fake.on("GET", path, contaminated(example.value));
        success(
          await client.callTool({
            name: "get_order",
            arguments: { orderId: order.orderId },
          }),
          order,
        );
        request(fake, path);
      }));
  }
  for (const [name, example] of Object.entries(
    examples("/api/v1/commissions"),
  )) {
    test(`공식 수수료 ${name}의 전체 시장·비율·KST 날짜`, async () =>
      run(async (fake, client) => {
        fake.on("GET", "/api/v1/commissions", contaminated(example.value));
        success(
          await client.callTool({ name: "get_commissions", arguments: {} }),
          example.value.result,
        );
        request(fake, "/api/v1/commissions");
      }));
  }

  test("모든 주문 필드·unknown enum·큰 문자열 ID·decimal·null", async () =>
    run(async (fake, client) => {
      const order = {
        ...sampleOrder("922337203685477580812345678901234567890"),
        side: "FUTURE_SIDE",
        orderType: "FUTURE_TYPE",
        timeInForce: "FUTURE_TIF",
        status: "FUTURE_STATUS",
        currency: "FUTURE_CURRENCY",
        orderAmount: "12345678901234567890.123456789",
      };
      expect(Object.keys(order).sort()).toEqual(
        Object.keys(fixture.components.schemas.Order.properties).sort(),
      );
      expect(Object.keys(order.execution).sort()).toEqual(
        Object.keys(
          fixture.components.schemas.OrderExecution.properties,
        ).sort(),
      );
      const path = `/api/v1/orders/${order.orderId}`;
      fake.on("GET", path, contaminated({ result: order }));
      const projected = await client.callTool({
        name: "get_order",
        arguments: { orderId: order.orderId },
      });
      success(projected, order);
      request(fake, path);
      const body = projected.structuredContent as any;
      expect(Object.keys(body.result)).toEqual(
        Object.keys(fixture.components.schemas.Order.properties),
      );
      expect(Object.keys(body.result.execution)).toEqual(
        Object.keys(fixture.components.schemas.OrderExecution.properties),
      );
    }));

  test("optional 부재와 null·unknown 시장·모든 정상 행 유지", async () =>
    run(async (fake, client) => {
      const rows = [
        { marketCountry: "KR", commissionRate: "0.0001500" },
        {
          marketCountry: "US",
          commissionRate: "0.001",
          startDate: null,
          endDate: null,
        },
        {
          marketCountry: "FUTURE_MARKET",
          commissionRate: "-0.00000000000000000000001",
          startDate: "2026-01-01",
        },
      ];
      expect(Object.keys(rows[1]!).sort()).toEqual(
        Object.keys(fixture.components.schemas.Commission.properties).sort(),
      );
      fake.on("GET", "/api/v1/commissions", contaminated({ result: rows }));
      success(
        await client.callTool({ name: "get_commissions", arguments: {} }),
        rows,
      );
      const order: any = sampleOrder();
      delete order.price;
      delete order.canceledAt;
      fake.on("GET", `/api/v1/orders/${order.orderId}`, { result: order });
      success(
        await client.callTool({
          name: "get_order",
          arguments: { orderId: order.orderId },
        }),
        order,
      );
    }));

  test("빈 수수료 배열 유지", async () =>
    run(async (fake, client) => {
      fake.on("GET", "/api/v1/commissions", { result: [] });
      success(
        await client.callTool({ name: "get_commissions", arguments: {} }),
        [],
      );
    }));

  test("opaque ID의 숫자·길이를 제한하지 않고 안전하게 인코딩", async () =>
    run(async (fake, client) => {
      for (const orderId of [
        "opaque.with-dot_+한글😀",
        "opaque-" + "한글😀".repeat(128),
        "opaque-" + "x".repeat(4096),
      ]) {
        const order = sampleOrder(orderId);
        const path = `/api/v1/orders/${encodeURIComponent(orderId)}`;
        fake.on("GET", path, { result: order });
        success(
          await client.callTool({ name: "get_order", arguments: { orderId } }),
          order,
        );
        request(fake, path);
      }
    }));

  test("경로·query·fragment·percent·control·surrogate 주입은 인증 HTTP 0", async () =>
    run(async (fake, client) => {
      for (const orderId of [
        "",
        ".",
        "..",
        "../orders",
        "a/b",
        "a\\b",
        "a?b",
        "a#b",
        "%2e%2e",
        "%252f",
        "a\u0000",
        "a\n",
        "a\u007f",
        "a\u0085",
        ...Array.from({ length: 32 }, (_, i) => `a${String.fromCharCode(i)}`),
        ...Array.from(
          { length: 33 },
          (_, i) => `a${String.fromCharCode(127 + i)}`,
        ),
        "\ud800",
        "\udfff",
      ]) {
        const result = await client.callTool({
          name: "get_order",
          arguments: { orderId },
        });
        expect(result.isError).toBe(true);
        expect(JSON.parse((result.content as any)[0].text)).toEqual({
          error: { code: "TOSSINVEST_INVALID_INPUT" },
        });
      }
      expect(fake.requests).toHaveLength(0);
      expect(fake.issued).toBe(0);
    }));

  test("SDK strict로 caller 계좌·token·URL·추가 키를 인증 HTTP 0으로 거절", async () =>
    run(async (fake, client) => {
      for (const name of ["get_order", "get_commissions"]) {
        for (const key of [
          "account",
          "accountSeq",
          "accountNo",
          "token",
          "url",
          "extra",
          "marketCountry",
        ]) {
          const args = {
            ...(name === "get_order" ? { orderId: "safe" } : {}),
            [key]: "synthetic",
          };
          expect(
            (await client.callTool({ name, arguments: args })).isError,
          ).toBe(true);
        }
      }
      for (const orderId of [null, 123, true, {}, []])
        expect(
          (await client.callTool({ name: "get_order", arguments: { orderId } }))
            .isError,
        ).toBe(true);
      expect(
        (await client.callTool({ name: "get_order", arguments: {} })).isError,
      ).toBe(true);
      expect(fake.requests).toHaveLength(0);
      const listed = await client.listTools();
      expect(listed.tools.map((tool) => tool.name).sort()).toEqual([
        "get_commissions",
        "get_order",
      ]);
      expect(
        listed.tools.every((tool) => tool.annotations?.readOnlyHint === true),
      ).toBe(true);
    }));

  test("불완전한 공식 응답은 부분 결과 없이 거절", async () =>
    run(async (fake, client) => {
      fake.on("GET", "/api/v1/commissions", {
        result: [{ marketCountry: "KR" }],
      });
      const result = await client.callTool({
        name: "get_commissions",
        arguments: {},
      });
      expect(result.isError).toBe(true);
      expect(JSON.parse((result.content as any)[0].text)).toEqual({
        error: { code: "TOSSINVEST_UNAVAILABLE" },
      });
    }));
});
