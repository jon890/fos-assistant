import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, credentials, withMcp } from "./support.ts";

const official = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const calls = [
  [
    "get_rankings",
    "/api/v1/rankings",
    { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" },
  ],
  [
    "get_market_indicator_prices",
    "/api/v1/market-indicators/prices",
    { symbols: "KOSPI" },
  ],
  [
    "get_market_indicator_candles",
    "/api/v1/market-indicators/KOSPI/candles",
    { symbol: "KOSPI", interval: "1d" },
  ],
  [
    "get_market_indicator_investor_trading",
    "/api/v1/market-indicators/KOSPI/investor-trading",
    { symbol: "KOSPI", interval: "1d" },
  ],
] as const;
const secrets = {
  client_secret: "projection-synthetic-secret",
  access_token: "projection-synthetic-token",
  accountNo: "projection-synthetic-account",
};

// 성공 fixture의 모든 알려진 필드를 공식 원문에서 얻는다. 제품의 투영 함수는 쓰지 않는다.
function schemaFields(schema: any): any {
  if (schema.$ref)
    return schemaFields(
      official.components.schemas[schema.$ref.split("/").pop()],
    );
  if (!schema.allOf) return schema;
  const merged: any = { type: "object", properties: {}, required: [] };
  for (const part of [...schema.allOf, { ...schema, allOf: undefined }]) {
    const branch = schemaFields(part);
    Object.assign(merged.properties, branch.properties);
    merged.required.push(...(branch.required ?? []));
  }
  return merged;
}
function sample(schema: any, mode: string): any {
  schema = schemaFields(schema);
  const types = Array.isArray(schema.type) ? schema.type : [schema.type];
  if (mode === "nullable" && (schema.nullable || types.includes("null")))
    return null;
  const type = types.find((value: string) => value !== "null");
  if (type === "object")
    return Object.fromEntries(
      Object.entries(schema.properties ?? {})
        .filter(([key]) => mode !== "absent" || schema.required?.includes(key))
        .map(([key, value]) => [key, sample(value, mode)]),
    );
  if (type === "array") {
    const first = sample(schema.items, mode);
    const second = sample(schema.items, mode);
    if (second && typeof second === "object") {
      if (typeof second.rank === "number") second.rank = 2;
      if (typeof second.symbol === "string") second.symbol += "-second";
      if (typeof second.lastPrice === "string")
        second.lastPrice = "0.012500000000000000000001";
    }
    return [first, second];
  }
  if (type === "integer") return 1;
  if (type === "number") return 1.25;
  if (type === "boolean") return false;
  if (schema.format === "decimal") return "-0.123456789012345678901234567";
  if (schema.format === "date-time") return "2026-10-11T09:00:00+09:00";
  if (schema.format === "date") return "2026-10-11";
  if (schema.enum) return mode === "unknown" ? "FUTURE_ENUM" : schema.enum[0];
  return "synthetic-known-field";
}
function inject(value: any): any {
  if (Array.isArray(value)) return value.map(inject);
  if (value === null || typeof value !== "object") return value;
  return {
    ...Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, inject(item)]),
    ),
    ...secrets,
  };
}
async function verify(
  client: Client,
  fake: FakeToss,
  extensions: boolean,
  selected: ReadonlyArray<(typeof calls)[number]> = calls,
) {
  for (const [name, path, args] of selected) {
    const content =
      official.paths[path.replace("/KOSPI/", "/{symbol}/")].get.responses["200"]
        .content["application/json"];
    const examples = content.example
      ? [content.example]
      : Object.values(content.examples).map((entry: any) => entry.value);
    // 원형의 대용량 회귀 fixture처럼 공식 첫 행을 복제하고 배열 순서를 보존한다.
    const large = structuredClone(examples[0]);
    const arrays = {
      get_rankings: ["rankings", 100],
      get_market_indicator_candles: ["candles", 200],
      get_market_indicator_investor_trading: ["records", 100],
    } as const;
    if (name !== "get_market_indicator_prices") {
      const [key, count] = arrays[name];
      const first = large.result[key][0];
      large.result[key] = Array.from({ length: count }, (_, index) => ({
        ...structuredClone(first),
        ...(key === "rankings" ? { rank: index + 1 } : {}),
        ...(key === "candles" ? { closePrice: String(index + 1) } : {}),
        ...(key === "records"
          ? {
              updatedAt: `2026-10-11T09:00:${String(index % 60).padStart(2, "0")}+09:00`,
            }
          : {}),
      }));
    }
    const cases = extensions
      ? [sample(content.schema, "full"), large]
      : [
          ...examples,
          large,
          ...["full", "nullable", "absent", "unknown"].map((mode) =>
            sample(content.schema, mode),
          ),
        ];
    for (const response of cases) {
      fake.on("GET", path, {
        ...response,
        result: extensions ? inject(response.result) : response.result,
        extraEnvelope: secrets,
      });
      const received = await client.callTool({ name, arguments: args });
      expect(received.isError).toBeUndefined();
      const serialized = JSON.stringify(received);
      for (const secret of Object.values(secrets))
        expect(serialized).not.toContain(secret);
      const body = JSON.parse(
        (received.content as Array<{ text: string }>)[0]!.text,
      );
      expect(body.result).toEqual(response.result);
      expect(body.metadata).toEqual({});
      expect(JSON.parse(JSON.stringify(received.structuredContent))).toEqual(
        body,
      );
      for (const key of ["nextBefore", "nextUntil"]) {
        if (Object.hasOwn(response.result, key))
          expect(body[key]).toEqual(response.result[key]);
        else expect(Object.hasOwn(body, key)).toBe(false);
      }
    }
    fake.fail("GET", path, 500, "internal-error");
    const error = await client.callTool({ name, arguments: args });
    expect(error.isError).toBe(true);
    expect(JSON.stringify(error)).not.toContain(
      "upstream-error-text-for-tests",
    );
  }
}
for (const selected of calls.map((entry) => [entry])) {
  for (const extensions of [true, false]) {
    test(`source ${selected[0]![0]} SDK 전체의 ${extensions ? "추가 민감 키 제거" : "공식 전체 필드·순서·선택 부재·null·enum·decimal·페이지 보존"}`, async () => {
      const fake = new FakeToss();
      try {
        await withMcp(
          createTossinvestServer({ apiBase: fake.url, env: credentials }),
          (client) => verify(client, fake, extensions, selected),
        );
      } finally {
        fake.stop();
      }
    });
    test(`tracked stdio ${selected[0]![0]} SDK 전체의 ${extensions ? "추가 민감 키 제거" : "공식 전체 필드와 페이지 보존"}`, async () => {
      const fake = new FakeToss();
      const directory = await mkdtemp(join(tmpdir(), "ranking-projection-"));
      const client = new Client({ name: "ranking-projection", version: "1" });
      try {
        const preload = join(directory, "loopback.ts");
        // 추적된 production 묶음의 전송만 합성 HTTP로 바꾼다.
        await writeFile(
          preload,
          `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
        );
        await client.connect(
          new StdioClientTransport({
            command: process.execPath,
            args: [
              "--preload",
              preload,
              join(import.meta.dir, "../dist/tossinvest-mcp.js"),
            ],
            env: credentials,
            stderr: "pipe",
          }),
        );
        await verify(client, fake, extensions, selected);
      } finally {
        await client.close();
        fake.stop();
        await rm(directory, { recursive: true, force: true });
      }
    });
  }
}
