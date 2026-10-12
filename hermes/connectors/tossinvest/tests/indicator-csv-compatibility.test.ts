import { expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { createTossinvestServer } from "../src/server.ts";
import { registerIndicatorTools } from "../src/indicator-tools.ts";
import { Tossinvest } from "../src/client.ts";
import canonical from "./fixtures/openapi-1.2.24.json";
import { FakeToss, credentials, tool, withMcp } from "./support.ts";

const name = "get_market_indicator_prices";
const path = "/api/v1/market-indicators/prices";
const catalog = [
  "KOSPI",
  "KOSDAQ",
  "KR_BOND_2Y",
  "KR_BOND_3Y",
  "KR_BOND_5Y",
  "KR_BOND_10Y",
  "KR_BOND_20Y",
  "KR_BOND_30Y",
];
const requested = Array.from(
  { length: 200 },
  (_, index) => catalog[index % 8]!,
);
const invalid = [
  "",
  " ",
  ",",
  "KOSPI,",
  ",KOSPI",
  "KOSPI, ,KOSDAQ",
  "KOSPI,,KOSDAQ",
  "KOS PI",
  "KOS\tPI",
  "KR_BOND_ 2Y",
  "KOSPI,AAPL",
  "kospi",
  "KOSPI/other",
  "..",
  "KOSPI, .",
  "KOSPI%2CKOSDAQ",
  "KOSPI&count=1",
  "KOSPI;DROP TABLE",
  Array(201).fill("KOSPI").join(", "),
];
const example =
  canonical.paths[path].get.responses["200"].content["application/json"]
    .examples.bonds.value;

async function verify(client: Client, fake: FakeToss) {
  const listed = (await client.listTools()).tools;
  expect(listed).toHaveLength(33);
  expect(listed.every((entry) => entry.annotations?.readOnlyHint)).toBe(true);
  const schema = listed.find((entry) => entry.name === name)!.inputSchema;
  expect((schema.properties!.symbols as { type: string }).type).toBe("string");
  expect(schema.additionalProperties).toBe(false);
  for (const symbols of invalid) {
    expect(
      (await client.callTool({ name, arguments: { symbols } })).isError,
    ).toBe(true);
  }
  for (const symbols of [null, 123, ["KOSPI"]]) {
    expect(
      (await client.callTool({ name, arguments: { symbols } })).isError,
    ).toBe(true);
  }
  for (const key of ["account", "token", "apiBase"]) {
    expect(
      (
        await client.callTool({
          name,
          arguments: { symbols: "KOSPI, KOSDAQ", [key]: "caller-value" },
        })
      ).isError,
    ).toBe(true);
  }
  expect(fake.requests).toHaveLength(0);
  fake.on("GET", path, {
    ...example,
    result: example.result.map((row) => ({
      ...row,
      access_token: "synthetic-extra-secret",
    })),
  });
  for (const [symbols, normalized] of [
    ["KOSPI, KOSDAQ", "KOSPI,KOSDAQ"],
    [" \tKOSPI \t,\n KOSDAQ\r ", "KOSPI,KOSDAQ"],
    [catalog.join(", "), catalog.join(",")],
    [requested.join(", "), requested.join(",")],
    ["KOSDAQ,KOSPI,KOSDAQ", "KOSDAQ,KOSPI,KOSDAQ"],
  ]) {
    const { result, body } = await tool(client, name, { symbols });
    expect(result.isError).toBeUndefined();
    expect(body.result).toEqual(example.result);
    expect(result.structuredContent).toEqual(body);
    expect(JSON.stringify(result)).not.toContain("synthetic-extra-secret");
    expect(Object.fromEntries(fake.seen("GET", path).at(-1)!.query)).toEqual({
      symbols: normalized,
    });
  }
  expect(fake.seen("GET", path)).toHaveLength(5);
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
  expect(fake.requests).toHaveLength(6);
  expect(fake.seen("DELETE")).toHaveLength(0);
  expect(
    fake.seen("POST").filter((entry) => entry.path !== "/oauth2/token"),
  ).toHaveLength(0);
}

test("SDK 문자열 schema가 CSV 공백·200개 순서·중복을 보존하고 잘못된 입력은 HTTP 전에 거절한다", async () => {
  const fake = new FakeToss();
  try {
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env: credentials }),
      (client) => verify(client, fake),
    );
  } finally {
    fake.stop();
  }
});

for (const entry of ["src/server.ts", "dist/tossinvest-mcp.js"]) {
  test(`${entry} stdio에서 CSV 공백 정규화와 문자열 schema를 유지한다`, async () => {
    const fake = new FakeToss();
    const directory = await mkdtemp(join(tmpdir(), "indicator-csv-"));
    const client = new Client({ name: "indicator-csv", version: "1" });
    try {
      const preload = join(directory, "loopback.ts");
      // 공식 origin을 유지하고 시험 전송만 합성 HTTP로 보낸다.
      await writeFile(
        preload,
        `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
      );
      await client.connect(
        new StdioClientTransport({
          command: process.execPath,
          args: ["--preload", preload, join(import.meta.dir, "..", entry)],
          env: credentials,
          stderr: "pipe",
        }),
      );
      await verify(client, fake);
    } finally {
      await client.close();
      fake.stop();
      await rm(directory, { recursive: true, force: true });
    }
  });
}

test("직접 핸들러도 CSV를 검증해 HTTP 전에 거절하고 같은 순서로 정규화한다", async () => {
  const fake = new FakeToss();
  let run!: (input: { symbols: string }) => Promise<unknown>;
  registerIndicatorTools(
    (registered, _schema, _annotations, handler) => {
      if (registered === name) run = handler;
    },
    new Tossinvest({ apiBase: fake.url, env: credentials }),
  );
  try {
    for (const symbols of invalid) {
      await expect(run({ symbols })).rejects.toThrow(
        "TOSSINVEST_INVALID_INPUT",
      );
    }
    expect(fake.requests).toHaveLength(0);
    fake.on("GET", path, example);
    expect(await run({ symbols: requested.join(", ") })).toEqual({
      result: example.result,
      metadata: {},
    });
    expect(fake.seen("GET", path)[0]!.query.get("symbols")).toBe(
      requested.join(","),
    );
  } finally {
    fake.stop();
  }
});
