import { expect, test } from "bun:test";
import { Tossinvest, clientStateSize, clearClientState } from "../src/client.ts";
import { GROUP_LIMITS, groupLimit, queueRequest, clearRateLimits, rateLimitStateSize } from "../src/rate-limit.ts";
import { FakeToss, credentials, json, apiError } from "./support.ts";

test("19그룹 예산과 KST 피크 전후를 제어 시계로 검증한다", async () => {
  for (const [group, limit] of Object.entries(GROUP_LIMITS)) {
    let now = Date.now() + 10000;
    const arrived: number[] = [];
    const clock = { now: () => now, sleep: async (ms: number) => { now += ms; } };
    const identity = `clock-${group}`;
    await queueRequest(identity, group, now + 10000, async () => { arrived.push(now); }, clock);
    await queueRequest(identity, group, now + 10000, async () => { arrived.push(now); }, clock);
    expect(arrived[1]! - arrived[0]!).toBe(Math.ceil(1000 / groupLimit(group, arrived[1]!)));
    expect(limit).toBeGreaterThan(0);
  }
  for (const [time, limit] of [["2026-01-01T23:59:59Z", 6], ["2026-01-02T00:00:00Z", 3], ["2026-01-02T00:09:59Z", 3], ["2026-01-02T00:10:00Z", 6]] as const) expect(groupLimit("ORDER_INFO", Date.parse(time))).toBe(limit);
  clearRateLimits();
});

test("같은 identity는 한 번 발급하며 secret/origin은 토큰을 분리한다", async () => {
  const a = new FakeToss(); const b = new FakeToss();
  try {
    const first = new Tossinvest({ apiBase: a.url, env: credentials });
    const same = new Tossinvest({ apiBase: `${a.url}/`, env: { ...credentials, TOSSINVEST_CLIENT_ID: ` ${credentials.TOSSINVEST_CLIENT_ID} ` } });
    expect(await Promise.all([first.token(), same.token(), first.token()])).toEqual(["fake-access-token-1", "fake-access-token-1", "fake-access-token-1"]);
    await new Tossinvest({ apiBase: a.url, env: { ...credentials, TOSSINVEST_CLIENT_SECRET: "changed-secret" } }).token();
    await new Tossinvest({ apiBase: b.url, env: credentials }).token();
    expect(a.issued).toBe(2); expect(b.issued).toBe(1);
  } finally { a.stop(); b.stop(); }
  expect(clientStateSize()).toBe(0);
});

test("교체 중 발급은 원래 secret identity에만 남는다", async () => {
  const fake = new FakeToss(); let release!: () => void; let started!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const arrived = new Promise<void>(resolve => { started = resolve; });
  const env = { ...credentials };
  fake.routes.set("POST /oauth2/token", async request => {
    const old = new URLSearchParams(request.body).get("client_secret") === credentials.TOSSINVEST_CLIENT_SECRET;
    if (old) { started(); await held; }
    return json({ access_token: old ? "old" : "new", expires_in: 3600 });
  });
  try {
    const client = new Tossinvest({ apiBase: fake.url, env });
    const old = client.token(); await arrived;
    env.TOSSINVEST_CLIENT_SECRET = "replacement";
    const next = client.token(); release();
    expect(await old).toBe("old"); expect(await next).toBe("new"); expect(await client.token()).toBe("new");
    expect(fake.seen("POST")).toHaveLength(2);
  } finally { release(); fake.stop(); }
});

test("늦은 401은 다른 요청이 받은 새 토큰을 버리지 않는다", async () => {
  const fake = new FakeToss(); let release!: () => void; let started!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const arrived = new Promise<void>(resolve => { started = resolve; });
  let first = true;
  fake.routes.set("GET /api/v1/prices", async request => {
    if (first) { first = false; started(); await held; return apiError(401, "token-revoked")(); }
    expect(request.headers.get("authorization")).toBe("Bearer fake-access-token-2");
    return json({ result: [] });
  });
  fake.sequence("GET", "/api/v1/stocks", [apiError(401, "token-revoked"), () => json({ result: [] })]);
  try {
    const a = new Tossinvest({ apiBase: fake.url, env: credentials });
    const b = new Tossinvest({ apiBase: fake.url, env: credentials });
    const slow = a.request("/api/v1/prices"); await arrived;
    await b.request("/api/v1/stocks"); release(); await slow;
    expect(fake.issued).toBe(2);
  } finally { release(); fake.stop(); }
});

test("낮은 헤더 한도와 상대 Reset이 HTTP 간격에 반영된다", async () => {
  const fake = new FakeToss(); const arrivals: number[] = [];
  fake.routes.set("GET /api/v1/prices", () => { arrivals.push(Date.now()); return json({ result: [] }, 200, { "x-ratelimit-limit": "2", "x-ratelimit-remaining": "0", "x-ratelimit-reset": "0.7" }); });
  try {
    const client = new Tossinvest({ apiBase: fake.url, env: credentials });
    await client.request("/api/v1/prices"); await client.request("/api/v1/prices");
    expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(700);
  } finally { fake.stop(); }
});

test("큐 대기도 deadline에 포함하고 만료한 대기는 HTTP를 보내지 않는다", async () => {
  const fake = new FakeToss(); fake.on("GET", "/api/v1/accounts", { result: [] });
  try {
    const client = new Tossinvest({ apiBase: fake.url, env: credentials, timeoutMs: 150 });
    await client.request("/api/v1/accounts");
    await expect(client.request("/api/v1/accounts")).rejects.toMatchObject({ code: "TOSSINVEST_NETWORK" });
    expect(fake.seen("GET")).toHaveLength(1);
  } finally { fake.stop(); }
});

test("실패와 만료, teardown은 identity와 큐 참조를 거둔다", async () => {
  const fake = new FakeToss();
  try {
    fake.on("POST", "/oauth2/token", { access_token: "short", expires_in: 0 });
    await expect(new Tossinvest({ apiBase: fake.url, env: credentials }).token()).rejects.toMatchObject({ code: "TOSSINVEST_UNAVAILABLE" });
    expect(clientStateSize()).toBe(0);
    fake.on("POST", "/oauth2/token", { access_token: "short", expires_in: 1 });
    await new Tossinvest({ apiBase: fake.url, env: credentials }).token();
    expect(clientStateSize()).toBe(1); await Bun.sleep(1010); expect(clientStateSize()).toBe(0);
  } finally { fake.stop(); clearClientState(); }
  expect(rateLimitStateSize()).toBe(0);
});

test("origin에 사용자정보와 경로, query, fragment를 넣지 못한다", () => {
  for (const apiBase of ["http://user@127.0.0.1", "http://127.0.0.1/extra", "http://127.0.0.1?q=1", "http://127.0.0.1/#x", "https://example.com"]) expect(() => new Tossinvest({ apiBase })).toThrow();
});

test("두 실제 Bun 프로세스는 예산과 토큰을 공유하지 않고 반복 철회에서 유한 실패한다", async () => {
  const fake = new FakeToss(); fake.fail("GET", "/api/v1/prices", 401, "token-revoked");
  const source = `import { Tossinvest } from ${JSON.stringify(`${import.meta.dir}/../src/client.ts`)}; try { await new Tossinvest({ apiBase: process.env.FAKE_TOSS_ORIGIN, env: process.env }).request('/api/v1/prices'); process.exitCode = 3; } catch (error) { console.log(error.code); }`;
  try {
    const children = [0, 1].map(() => Bun.spawn([process.execPath, "--eval", source], { env: { ...process.env, ...credentials, FAKE_TOSS_ORIGIN: fake.url }, stdout: "pipe", stderr: "pipe" }));
    for (const child of children) {
      expect(await child.exited).toBe(0);
      expect(await new Response(child.stdout).text()).toBe("TOSSINVEST_UNAVAILABLE\n");
      expect(await new Response(child.stderr).text()).toBe("");
    }
    expect(fake.seen("GET")).toHaveLength(4); expect(fake.seen("POST")).toHaveLength(4);
  } finally { fake.stop(); }
});
