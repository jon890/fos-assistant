import { describe, expect, test } from "bun:test";
import { groupLimit } from "../src/rate-limit.ts";
import { Tossinvest } from "../src/client.ts";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, apiError, credentials, json, tool, withMcp } from "./support.ts";

const env = { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" };
const accountBodies = {
  "/api/v1/accounts": { result: [] },
  "/api/v1/holdings": { result: { items: [] } },
  "/api/v1/buying-power": { result: { currency: "KRW", cashBuyingPower: "1000" } },
  "/api/v1/sellable-quantity": { result: { sellableQuantity: "2" } },
  "/api/v1/orders": { result: { orders: [], hasNext: false } },
};

const groups = {
  "/api/v1/accounts": { name: "ACCOUNT", interval: 1_000 },
  "/api/v1/holdings": { name: "ASSET", interval: 200 },
  "/api/v1/buying-power": { name: "ORDER_INFO", interval: Math.ceil(1000 / groupLimit("ORDER_INFO", Date.now())) },
  "/api/v1/sellable-quantity": { name: "ORDER_INFO", interval: Math.ceil(1000 / groupLimit("ORDER_INFO", Date.now())) },
  "/api/v1/orders": { name: "ORDER_HISTORY", interval: 200 },
} as const;

/** 같은 공식 그룹의 요청이 겹치거나 한도 간격 안에 다시 오면 429로 답한다. */
function limitedAccountRoutes(fake: FakeToss) {
  const arrivals: Array<{ group: string; at: number; interval: number }> = [];
  const active = new Set<string>();
  const last = new Map<string, number>();
  let rejected = 0;
  for (const [path, body] of Object.entries(accountBodies)) {
    fake.routes.set(`GET ${path}`, async () => {
      const group = groups[path as keyof typeof groups];
      const now = performance.now();
      arrivals.push({ group: group.name, at: now, interval: group.interval });
      if (active.has(group.name) || now - (last.get(group.name) ?? -Infinity) < group.interval) {
        rejected += 1;
        return apiError(429, "too-many-requests")();
      }
      active.add(group.name);
      last.set(group.name, now);
      try {
        await Bun.sleep(20);
        return json(body);
      } finally {
        active.delete(group.name);
      }
    });
  }
  return { arrivals, rejected: () => rejected };
}

describe("계좌 조회 호출 간격", () => {
  test("대역은 같은 그룹에 바로 보낸 두 요청 가운데 하나를 429로 거절한다", async () => {
    const fake = new FakeToss();
    const limit = limitedAccountRoutes(fake);
    try {
      const responses = await Promise.all([
        fetch(`${fake.url}/api/v1/buying-power`),
        fetch(`${fake.url}/api/v1/sellable-quantity`),
      ]);
      expect(responses.map((response) => response.status).sort()).toEqual([200, 429]);
      expect(limit.rejected()).toBe(1);
      await Promise.all(responses.map((response) => response.text()));
    } finally {
      fake.stop();
    }
  });

  test("동시에 부른 계좌 도구를 공식 그룹별로 보내고 buying_power의 두 요청은 간격을 지킨다", async () => {
    const fake = new FakeToss();
    const limit = limitedAccountRoutes(fake);
    try {
      await withMcp(createTossinvestServer({ apiBase: fake.url, env }), async (client) => {
        const results = await Promise.all([
          tool(client, "get_buying_power", { currency: "KRW", symbol: "005930" }),
          tool(client, "get_holdings"),
          tool(client, "list_orders", { status: "OPEN" }),
          tool(client, "list_accounts"),
        ]);
        for (const result of results) expect(result.result.isError).toBeUndefined();
        expect(results[0]!.body).toEqual({
          currency: "KRW", cash_buying_power: "1000", sellable_quantity: "2",
        });
      });
      expect(limit.arrivals).toHaveLength(5);
      expect(limit.rejected()).toBe(0);
      const orderInfo = limit.arrivals.filter((arrival) => arrival.group === "ORDER_INFO");
      expect(orderInfo).toHaveLength(2);
      expect(orderInfo[1]!.at - orderInfo[0]!.at).toBeGreaterThanOrEqual(Math.ceil(1000 / groupLimit("ORDER_INFO", Date.now())));
    } finally {
      fake.stop();
    }
  }, 15_000);

  test("다른 클라이언트도 프로세스의 큐를 공유하고 시세 조회는 기다리지 않는다", async () => {
    const fake = new FakeToss();
    const limit = limitedAccountRoutes(fake);
    const first = new Tossinvest({ apiBase: fake.url, env });
    const second = new Tossinvest({ apiBase: fake.url, env });
    fake.on("GET", "/api/v1/prices", { result: [] });
    try {
      await Promise.all([first.token(), second.token()]);
      const pending = [
        first.request("/api/v1/accounts"),
        second.request("/api/v1/accounts"),
      ];
      await second.request("/api/v1/prices");
      expect(fake.seen("GET", "/api/v1/accounts").length).toBeLessThan(2);
      await Promise.all(pending);
      expect(limit.rejected()).toBe(0);
      expect(limit.arrivals[1]!.at - limit.arrivals[0]!.at).toBeGreaterThanOrEqual(1_000);
    } finally {
      fake.stop();
    }
  }, 10_000);

  test("느린 ACCOUNT 요청이 다른 계좌 그룹의 요청을 막지 않는다", async () => {
    const fake = new FakeToss();
    let arrived!: () => void;
    let release!: () => void;
    const started = new Promise<void>((resolve) => { arrived = resolve; });
    const held = new Promise<void>((resolve) => { release = resolve; });
    fake.routes.set("GET /api/v1/accounts", async () => {
      arrived();
      await held;
      return json(accountBodies["/api/v1/accounts"]);
    });
    fake.on("GET", "/api/v1/holdings", accountBodies["/api/v1/holdings"]);
    const client = new Tossinvest({ apiBase: fake.url, env });
    const pending = client.request("/api/v1/accounts");
    try {
      await started;
      expect(await client.request("/api/v1/holdings", { account: true })).toEqual(
        accountBodies["/api/v1/holdings"],
      );
    } finally {
      release();
      await pending;
      fake.stop();
    }
  }, 10_000);

  test("여러 클라이언트의 ORDER_INFO 요청도 한 큐에서 피크 시간 간격을 지킨다", async () => {
    const fake = new FakeToss();
    const limit = limitedAccountRoutes(fake);
    const first = new Tossinvest({ apiBase: fake.url, env });
    const second = new Tossinvest({ apiBase: fake.url, env });
    try {
      await Promise.all([
        first.request("/api/v1/buying-power", { account: true }),
        second.request("/api/v1/sellable-quantity", { account: true }),
        first.request("/api/v1/sellable-quantity", { account: true }),
        second.request("/api/v1/buying-power", { account: true }),
      ]);
      expect(limit.rejected()).toBe(0);
      expect(limit.arrivals).toHaveLength(4);
      for (let index = 1; index < limit.arrivals.length; index++)
        expect(limit.arrivals[index]!.at - limit.arrivals[index - 1]!.at).toBeGreaterThanOrEqual(Math.ceil(1000 / groupLimit("ORDER_INFO", Date.now())));
    } finally {
      fake.stop();
    }
  }, 10_000);

  test("429의 Retry-After가 그룹 간격보다 길면 그 시간까지 기다린다", async () => {
    const fake = new FakeToss();
    const arrivals: number[] = [];
    fake.sequence("GET", "/api/v1/buying-power", [
      () => {
        arrivals.push(performance.now());
        return json({ error: { code: "too-many-requests" } }, 429, { "retry-after": "1.2" });
      },
      () => { arrivals.push(performance.now()); return json(accountBodies["/api/v1/buying-power"]); },
    ]);
    try {
      const client = new Tossinvest({ apiBase: fake.url, env });
      expect(await client.request("/api/v1/buying-power", { account: true })).toEqual(
        accountBodies["/api/v1/buying-power"],
      );
      expect(arrivals).toHaveLength(2);
      expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(1_200);
    } finally {
      fake.stop();
    }
  }, 10_000);

  test("429는 1초 이상 기다려 한 번만 재송하고, 다시 429여도 다음 요청은 보낸다", async () => {
    const fake = new FakeToss();
    const arrivals: number[] = [];
    fake.sequence("GET", "/api/v1/accounts", [
      () => { arrivals.push(performance.now()); return apiError(429, "too-many-requests")(); },
      () => { arrivals.push(performance.now()); return json({ result: [] }); },
    ]);
    const client = new Tossinvest({ apiBase: fake.url, env });
    try {
      expect(await client.request("/api/v1/accounts")).toEqual({ result: [] });
      expect(arrivals).toHaveLength(2);
      expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(1_000);
      expect(fake.issued).toBe(1);
      fake.fail("GET", "/api/v1/accounts", 429, "too-many-requests");
      await expect(client.request("/api/v1/accounts")).rejects.toMatchObject({
        code: "TOSSINVEST_RATE_LIMITED",
      });
      expect(fake.seen("GET", "/api/v1/accounts")).toHaveLength(4);
      fake.on("GET", "/api/v1/accounts", { result: [] });
      expect(await client.request("/api/v1/accounts")).toEqual({ result: [] });
    } finally {
      fake.stop();
    }
  }, 15_000);

  test("401 재발급 뒤에도 간격을 지키고, 429 재시도는 요청 전체에서 한 번이다", async () => {
    const fake = new FakeToss();
    const arrivals: number[] = [];
    const responses = [
      apiError(429, "too-many-requests"),
      apiError(401, "token-revoked"),
      apiError(429, "too-many-requests"),
    ];
    fake.sequence("GET", "/api/v1/accounts", responses.map((response) => () => {
      arrivals.push(performance.now());
      return response();
    }));
    try {
      const client = new Tossinvest({ apiBase: fake.url, env });
      await expect(client.request("/api/v1/accounts")).rejects.toMatchObject({
        code: "TOSSINVEST_RATE_LIMITED",
      });
      expect(arrivals).toHaveLength(3);
      expect(fake.issued).toBe(2);
      for (let index = 1; index < arrivals.length; index++)
        expect(arrivals[index]! - arrivals[index - 1]!).toBeGreaterThanOrEqual(1_000);
    } finally {
      fake.stop();
    }
  }, 10_000);
});
