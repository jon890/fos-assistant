import { expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import {
  CURRENT_OPERATIONS,
  OPERATIONS,
  matchesOperation,
} from "../src/api-contract.ts";
import canonical from "./fixtures/openapi-1.2.24.json";
import manifest from "../connector.json";
import { FakeToss, credentials, tool } from "./support.ts";
import {
  readUnionCases,
  readOperationIds,
  readToolNames,
} from "./read-union-cases.ts";

for (const entry of ["src/server.ts", "dist/tossinvest-mcp.js"]) {
  test(`${entry}의 READ 33개가 공식 GET 34개와 인증에 도달한다`, async () => {
    const expected = new Set(["issueOAuth2Token", ...readOperationIds]);
    expect(expected.size).toBe(35);
    expect(readToolNames).toHaveLength(33);
    expect(CURRENT_OPERATIONS).toEqual(expected);
    const fake = new FakeToss(expected);
    const directory = await mkdtemp(join(tmpdir(), "toss-read-union-"));
    const client = new Client({ name: "read-union", version: "1.0" });
    const examples = new Map<string, any>();
    for (const call of readUnionCases) {
      for (const id of call.operations) {
        const operation = OPERATIONS.find((operation) => operation.id === id)!;
        const path = operation.path.replace(/\{(\w+)\}/g, (_, key) =>
          encodeURIComponent(String(call.args[key])),
        );
        const content = (canonical.paths as any)[operation.path].get.responses[
          "200"
        ].content["application/json"];
        const example =
          content.example ?? (Object.values(content.examples)[0] as any).value;
        examples.set(id, example);
        fake.on("GET", path, example);
      }
    }
    try {
      const preload = join(directory, "loopback.ts");
      await writeFile(
        preload,
        `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
      );
      await client.connect(
        new StdioClientTransport({
          command: process.execPath,
          args: ["--preload", preload, join(import.meta.dir, "..", entry)],
          env: {
            ...credentials,
            TOSSINVEST_ACCOUNT_SEQ: "9223372036854775807",
          },
          stderr: "pipe",
        }),
      );
      const listed = (await client.listTools()).tools;
      // READ 33개는 그대로이고 주문 준비 READ 하나와 실행 FINANCIAL 둘이 따로 있다.
      const financial = ["cancel_order", "create_order"];
      const trading = [...financial, "prepare_trade"];
      expect(
        listed
          .map(({ name }) => name)
          .filter((name) => !trading.includes(name))
          .sort(),
      ).toEqual(readToolNames);
      expect(listed.map(({ name }) => name).sort()).toEqual(
        Object.keys(manifest.tools).sort(),
      );
      for (const item of listed) {
        const declared = (manifest.tools as Record<string, any>)[item.name];
        const read = !financial.includes(item.name);
        expect(declared.risk).toBe(read ? "READ" : "FINANCIAL");
        expect(declared.approval ?? "none").toBe(read ? "none" : "always");
        expect(item.description).toBe(declared.description);
        expect(item.description).not.toContain("undefined");
        expect(item.annotations?.readOnlyHint).toBe(read);
        expect(item.inputSchema.additionalProperties).toBe(false);
      }
      for (const call of readUnionCases) {
        for (const extra of [
          { account: "caller-account" },
          { token: "caller-token" },
          { url: "https://invalid.example" },
        ]) {
          const response = await client.callTool({
            name: call.name,
            arguments: { ...call.args, ...extra },
          });
          expect(response.isError).toBe(true);
          expect(fake.requests).toHaveLength(0);
        }
      }
      for (const call of readUnionCases) {
        const before = fake.seen("GET").length;
        const response = await tool(client, call.name, call.args);
        expect(response.result.isError).toBeUndefined();
        expect(response.result.structuredContent).toEqual(response.body);
        expect(
          fake
            .seen("GET")
            .slice(before)
            .map(
              (request) =>
                OPERATIONS.find((operation) =>
                  matchesOperation(operation, request.method, request.path),
                )!.id,
            ),
        ).toEqual(call.operations);
        if (call.name === "list_accounts") {
          const rows = response.body.result as Array<any>;
          expect(rows).toEqual(
            examples.get("getAccounts").result.map((row: any) => ({
              ...row,
              accountSeq: String(row.accountSeq),
              accountNo: `****${row.accountNo.slice(-4)}`,
            })),
          );
        } else if (call.name === "get_quotes") {
          expect(response.body.result).toEqual({
            prices: examples.get("getPrices").result,
            stocks: examples.get("getStocks").result,
          });
        } else if (call.name === "get_buying_power") {
          expect(response.body.result).toEqual({
            buyingPower: examples.get("getBuyingPower").result,
            sellableQuantity: examples.get("getSellableQuantity").result,
          });
        } else {
          expect(response.body.result).toEqual(
            examples.get(call.operations[0]!).result,
          );
        }
      }
      expect(fake.seen("GET")).toHaveLength(36);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      expect(
        fake.seen("POST").filter(({ path }) => path !== "/oauth2/token"),
      ).toHaveLength(0);
      expect(fake.seen("DELETE")).toHaveLength(0);
      expect(fake.requests).toHaveLength(37);
      expect(
        new Set(
          fake.requests.map(
            (request) =>
              OPERATIONS.find((operation) =>
                matchesOperation(operation, request.method, request.path),
              )!.id,
          ),
        ),
      ).toEqual(expected);
      for (const request of fake.seen("GET")) {
        const operation = OPERATIONS.find((operation) =>
          matchesOperation(operation, request.method, request.path),
        )!;
        const account = (canonical.paths as any)[
          operation.path
        ].get.parameters?.some(
          (parameter: any) =>
            parameter.$ref === "#/components/parameters/AccountSeq",
        );
        expect(request.headers.get("x-tossinvest-account")).toBe(
          account ? "9223372036854775807" : null,
        );
        expect(request.headers.get("authorization")).toBe(
          "Bearer fake-access-token-1",
        );
        expect(request.body).toBe("");
      }
    } finally {
      await client.close();
      fake.stop();
      await rm(directory, { recursive: true, force: true });
    }
  });
}
