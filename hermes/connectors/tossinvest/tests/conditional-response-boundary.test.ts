import { expect, test } from "bun:test";
import canonical from "./fixtures/openapi-1.2.24.json";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, accountNo, credentials, tool, withMcp } from "./support.ts";

test("실제 SDK 조건조회는 모든 공식 필드와 부재를 보존하고 중첩 확장 키를 제거한다", async () => {
  const path = "/api/v1/conditional-orders";
  const official =
    canonical.paths["/api/v1/conditional-orders/{conditionalOrderId}"].get
      .responses["200"].content["application/json"].example.result;
  const fake = new FakeToss(
    new Set([
      "issueOAuth2Token",
      "getConditionalOrders",
      "getConditionalOrder",
    ]),
  );
  const extension = {
    accountNo,
    token: credentials.TOSSINVEST_CLIENT_SECRET,
    nested: { rows: [{ access_token: credentials.TOSSINVEST_CLIENT_ID }] },
  };
  const known = {
    ...official,
    type: "FUTURE_TYPE",
    status: "FUTURE_STATUS",
    quantity: "9007199254740993.000000000000000000001",
    first: {
      ...official.first,
      type: "PROFIT_RATE",
      status: "HOLDING",
      triggerPrice: null,
      targetProfitRate: "0.7777777777777777777777777",
      triggeredOrderId: "opaque-child",
    },
    second: { ...official.second!, status: "CANCELED" },
  };
  const extended = {
    ...known,
    ...extension,
    first: { ...known.first, ...extension },
    second: { ...known.second, ...extension },
  };
  try {
    await withMcp(
      createTossinvestServer({
        apiBase: fake.url,
        env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "9223372036854775807" },
      }),
      async (client) => {
        for (const omit of [false, true]) {
          const expected: any = structuredClone(known);
          const upstream: any = structuredClone(extended);
          if (omit) {
            delete expected.expireDate;
            delete upstream.expireDate;
            expected.second = upstream.second = null;
          }
          fake.on("GET", `${path}/${official.conditionalOrderId}`, {
            result: upstream,
            metadata: extension,
            ...extension,
          });
          fake.on("GET", path, {
            result: {
              conditionalOrders: [upstream],
              hasNext: false,
              ...extension,
            },
            metadata: extension,
            ...extension,
          });
          const single = await tool(client, "get_conditional_order", {
            conditionalOrderId: official.conditionalOrderId,
          });
          expect(single.result.isError).toBeUndefined();
          expect(single.body).toEqual({ result: expected, metadata: {} });
          const listing = await tool(client, "list_conditional_orders", {
            status: "CLOSED",
          });
          expect(listing.result.isError).toBeUndefined();
          expect(listing.body.result).toEqual({
            conditionalOrders: [expected],
            hasNext: false,
          });
          expect(listing.body.metadata).toEqual({});
          for (const response of [single.result, listing.result]) {
            const serialized = JSON.stringify(response);
            for (const value of [accountNo, ...Object.values(credentials)])
              expect(serialized).not.toContain(value);
            expect(serialized).not.toContain("nested");
            expect(response.structuredContent).toBeDefined();
          }
        }
      },
    );
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(fake.seen("GET")).toHaveLength(4);
    expect(fake.seen("DELETE")).toHaveLength(0);
  } finally {
    fake.stop();
  }
});
