import { expect, test } from "bun:test";
import { readFile, writeFile, rm, readdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import {
  ClaimFixture,
  generateTradingFixture,
} from "./fixtures/trading-entry.ts";
import {
  FakeToss,
  credentials,
  stockInfo,
  sampleOrder,
  tool,
} from "./support.ts";
import { OPERATIONS } from "../src/api-contract.ts";
import { expectedOrder, tradingBody, UNKNOWN } from "../src/trading-body.ts";

const account = "9223372036854775807";
const env = () => ({ ...credentials, TOSSINVEST_ACCOUNT_SEQ: account });
const invalid = { error: { code: "TOSSINVEST_INVALID_INPUT" } };

function setup(amount = false) {
  const fake = new FakeToss(
    new Set(OPERATIONS.map((operation) => operation.id)),
  );
  const original = {
    ...sampleOrder("old"),
    symbol: "ABC",
    quantity: "2",
    orderType: amount ? "MARKET" : "LIMIT",
    price: amount ? null : "12",
    orderAmount: amount ? "50" : null,
  };
  fake.on("GET", "/api/v1/orders/old", { result: original });
  fake.on("GET", "/api/v1/stocks", { result: [stockInfo("ABC", "합성 종목")] });
  fake.routes.set(
    "GET /api/v1/accounts",
    () =>
      new Response(
        `{"result":[{"accountSeq":${account},"accountNo":"00001234","accountType":"BROKERAGE"}]}`,
        { headers: { "content-type": "application/json" } },
      ),
  );
  return { fake, original };
}

async function connect(root: string, destination: string, authority = {}) {
  const client = new Client({ name: "review-regression", version: "1" });
  await client.connect(
    new StdioClientTransport({
      command: process.execPath,
      args: [join(root, "dist/tossinvest-mcp.js"), "--serve", destination],
      env: { ...env(), ...authority },
      stderr: "pipe",
    }),
  );
  return client;
}

async function consume(root: string, prepared: unknown, model: unknown) {
  await writeFile(
    join(root, "prepared.json"),
    JSON.stringify({ prepared, model, tool: "modify_order" }),
  );
  const child = Bun.spawn(
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
  const [out, err, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ]);
  console.info(`Python 소비자: exit=${code} ${code ? err : out.trim()}`);
  expect(code, err).toBe(0);
  expect(out.trim()).toMatch(/^[0-9a-f]{64}$/);
}

for (const [amount, target] of [
  [true, "LIMIT"],
  [true, "MARKET"],
  [false, "LIMIT"],
  [false, "MARKET"],
] as const) {
  test(`원주문 GET→SDK prepare→Python: ${amount ? "금액" : "수량"}→${target}`, async () => {
    const { fake, original } = setup(amount);
    const claims = new ClaimFixture();
    const root = await generateTradingFixture(fake.url);
    const prepare = await connect(root, fake.url);
    let execute: Client | undefined;
    try {
      const model = {
        orderId: "old",
        orderType: target,
        ...(target === "LIMIT" ? { price: "13" } : {}),
      };
      const prepared = await tool(prepare, "prepare_trade", {
        v: 1,
        tool: "modify_order",
        args: model,
      });
      for (const item of (await prepare.listTools()).tools.filter((item) =>
        [
          "prepare_trade",
          "create_order",
          "modify_order",
          "cancel_order",
        ].includes(item.name),
      )) {
        expect(item.description).toContain("quantity는 주");
        expect(item.description).toContain("KR price는 원");
        expect(item.description).toContain("US price와 orderAmount는 달러");
        expect(item.description).toContain("둘을 함께 넣지");
        if (["modify_order", "cancel_order"].includes(item.name))
          expect(item.description).toContain(
            "UNKNOWN이면 새 권한이나 UUID로 자동 재전송하지",
          );
        // 운영 manifest의 prepare_trade 설명은 정정을 열지 않으므로 정정 도구만 이 제한을 적는다.
        if (item.name === "modify_order")
          expect(item.description).toContain("금액 원주문을 LIMIT로 정정");
      }
      const unsupported = amount && target === "LIMIT";
      // 성공 결과는 실제 소비자까지 확인한다. 수정 전에는 LIMIT와 금액의 불일치가 드러난다.
      if (!prepared.result.isError) await consume(root, prepared.body, model);
      expect(Boolean(prepared.result.isError)).toBe(unsupported);
      expect(fake.seen("GET", "/api/v1/orders/old")).toHaveLength(1);
      const args = unsupported
        ? {
            ...model,
            account_seq: account,
            market: "NASDAQ",
            currency: "USD",
            expected_order: expectedOrder(original),
          }
        : (prepared.body.executionArgs as any);
      if (unsupported) {
        expect(prepared.body).toEqual(invalid);
        expect(() => tradingBody("modify_order", args)).toThrow(
          "TOSSINVEST_INVALID_INPUT",
        );
      }
      fake.on("POST", "/api/v1/orders/old/modify", {
        result: { orderId: "new" },
      });
      execute = await connect(
        root,
        fake.url,
        claims.authority("modify_order", args),
      );
      const start = fake.requests.length;
      const response = await tool(execute, "modify_order", args);
      expect(response.body).toEqual(unsupported ? invalid : { orderId: "new" });
      expect(claims.requests).toHaveLength(1);
      expect(
        fake.requests.slice(start).map((r) => `${r.method} ${r.path}`),
      ).toEqual(
        unsupported
          ? []
          : [
              "POST /oauth2/token",
              "GET /api/v1/orders/old",
              "POST /api/v1/orders/old/modify",
            ],
      );
      expect(fake.seen("POST", "/api/v1/orders/old/modify")).toHaveLength(
        unsupported ? 0 : 1,
      );
      expect(fake.seen("DELETE")).toHaveLength(0);
      console.info(
        `금액/수량 정정 amount=${amount} target=${target}: claim=1 금융POST=${unsupported ? 0 : 1} DELETE=0`,
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

for (const name of ["modify_order", "cancel_order"] as const) {
  test(`${name} 동일 ID 2xx는 UNKNOWN: 목적지 POST 1·추가 HTTP 0`, async () => {
    const { fake } = setup();
    const claims = new ClaimFixture();
    const root = await generateTradingFixture(fake.url);
    const prepare = await connect(root, fake.url);
    let execute: Client | undefined;
    try {
      const model = {
        orderId: "old",
        ...(name === "modify_order" ? { orderType: "LIMIT", price: "13" } : {}),
      };
      const prepared = await tool(prepare, "prepare_trade", {
        v: 1,
        tool: name,
        args: model,
      });
      expect(prepared.result.isError).toBeUndefined();
      const args = prepared.body.executionArgs as any;
      const destination = `/api/v1/orders/old/${name === "modify_order" ? "modify" : "cancel"}`;
      fake.on("POST", destination, { result: { orderId: "old" } });
      execute = await connect(root, fake.url, claims.authority(name, args));
      const start = fake.requests.length;
      const response = await tool(execute, name, args);
      expect(response.result.isError).toBe(true);
      expect(response.body).toEqual({ error: { code: UNKNOWN } });
      expect(claims.requests).toHaveLength(1);
      expect(
        fake.requests.slice(start).map((r) => `${r.method} ${r.path}`),
      ).toEqual([
        "POST /oauth2/token",
        "GET /api/v1/orders/old",
        `POST ${destination}`,
      ]);
      const end = fake.requests.length;
      expect((await tool(execute, name, args)).result.isError).toBe(true);
      expect(fake.requests).toHaveLength(end);
      expect(claims.requests).toHaveLength(1);
      expect(fake.seen("POST", destination)).toHaveLength(1);
      expect(fake.seen("DELETE")).toHaveLength(0);
      console.info(
        `${name}: claim=1 auth=1 GET=1 금융POST=1 DELETE=0 replayHTTP=0 ${UNKNOWN}`,
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

test("생산 금융 transport 호출자는 guarded trading-tools만 사용한다", async () => {
  const source = resolve(import.meta.dir, "../src");
  const callers: string[] = [];
  for (const file of await readdir(source)) {
    if (
      file.endsWith(".ts") &&
      /\.financial\s*\(/.test(await readFile(join(source, file), "utf8"))
    )
      callers.push(file);
  }
  expect(callers).toEqual(["trading-tools.ts"]);
  const trading = await readFile(join(source, callers[0]!), "utf8");
  expect(trading.indexOf("await claimApproval(")).toBeGreaterThanOrEqual(0);
  expect(trading.indexOf("tradingBody(tool, input)")).toBeGreaterThanOrEqual(0);
  expect(trading.indexOf("await claimApproval(")).toBeLessThan(
    trading.indexOf("await client.financial("),
  );
  expect(trading.indexOf("tradingBody(tool, input)")).toBeLessThan(
    trading.indexOf("await client.financial("),
  );
});
