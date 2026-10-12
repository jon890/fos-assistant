import { expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import { CURRENT_OPERATIONS } from "../src/api-contract.ts";
import manifest from "../connector.json";
import fixture from "./fixtures/openapi-1.2.24.json";
import { FakeToss, credentials, sampleOrder, tool } from "./support.ts";

const sentinel = "synthetic-sensitive-stdio-extension";

/** 공식 scalar는 그대로 두고 모든 객체에 확장키를 넣는다. */
function contaminated(value: any): any {
  if (Array.isArray(value)) return value.map(contaminated);
  if (value && typeof value === "object")
    return {
      ...Object.fromEntries(
        Object.entries(value).map(([key, child]) => [key, contaminated(child)]),
      ),
      token: sentinel,
      accountNo: sentinel,
    };
  return value;
}

for (const mode of ["source", "bundle"]) {
  test(`production ${mode} stdio의 목록·strict·인증 0·두 GET·계좌 마스킹`, async () => {
    const fake = new FakeToss(
      new Set([
        "issueOAuth2Token",
        "getOrder",
        "getCommissions",
        "getAccounts",
      ]),
    );
    const directory = await mkdtemp(join(tmpdir(), "order-read-stdio-"));
    const client = new Client({ name: "order-read-stdio", version: "1.0" });
    try {
      const preload = join(directory, "loopback.ts");
      // 공식 origin은 유지하고 이 시험 프로세스의 전송만 로컬 대역으로 돌린다.
      await writeFile(
        preload,
        `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
      );
      if (mode === "bundle") await buildBundle(directory);
      await client.connect(
        new StdioClientTransport({
          command: process.execPath,
          args: [
            "--preload",
            preload,
            mode === "source"
              ? join(import.meta.dir, "../src/server.ts")
              : join(directory, "tossinvest-mcp.js"),
          ],
          env: {
            ...credentials,
            TOSSINVEST_ACCOUNT_SEQ: "9223372036854775807",
          },
          stderr: "pipe",
        }),
      );
      const listed = (await client.listTools()).tools;
      expect(listed.map((item) => item.name).sort()).toEqual(
        Object.keys(manifest.tools).sort(),
      );
      expect(listed).toHaveLength(33);
      expect(CURRENT_OPERATIONS.size).toBe(35);
      for (const item of listed) {
        expect(item.description?.length).toBeGreaterThan(20);
        expect(item.description).not.toContain("undefined");
        expect(item.annotations?.readOnlyHint).toBe(true);
        expect(item.inputSchema.additionalProperties).toBe(false);
      }
      for (const name of ["get_order", "get_commissions"] as const) {
        expect(manifest.tools[name]).toEqual({
          risk: "READ",
          approval: "none",
          title: expect.any(String),
          summary: expect.any(String),
          description: listed.find((entry) => entry.name === name)!
            .description!,
        });
        for (const key of [
          "account",
          "accountSeq",
          "accountNo",
          "token",
          "url",
          "extra",
          "marketCountry",
        ])
          expect(
            (
              await client.callTool({
                name,
                arguments: {
                  ...(name === "get_order" ? { orderId: "safe" } : {}),
                  [key]: "synthetic",
                },
              })
            ).isError,
          ).toBe(true);
      }
      for (const orderId of [
        ...Array.from({ length: 32 }, (_, i) => `a${String.fromCharCode(i)}`),
        ...Array.from(
          { length: 33 },
          (_, i) => `a${String.fromCharCode(127 + i)}`,
        ),
        "\ud800",
        "\udfff",
        "",
        ".",
        "..",
        "a/b",
        "a\\b",
        "a?b",
        "a#b",
        "%2f",
      ])
        expect(
          (await client.callTool({ name: "get_order", arguments: { orderId } }))
            .isError,
        ).toBe(true);
      expect(fake.requests).toHaveLength(0);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(0);
      expect(fake.issued).toBe(0);

      const orderId = "opaque-" + "한글😀".repeat(128);
      const order = {
        ...sampleOrder(orderId),
        orderAmount: "98765432109876543210.001",
        orderedAt: "2026-10-11T09:01:02+09:00",
      };
      const orderPath = `/api/v1/orders/${encodeURIComponent(orderId)}`;
      const commissions = (
        fixture.paths["/api/v1/commissions"].get.responses["200"].content[
          "application/json"
        ].examples as any
      ).standard.value.result;
      fake.on("GET", orderPath, contaminated({ result: order }));
      fake.on(
        "GET",
        "/api/v1/commissions",
        contaminated({ result: commissions }),
      );
      for (const [name, args, expected] of [
        ["get_order", { orderId }, order],
        ["get_commissions", {}, commissions],
      ] as const) {
        const { result, body } = await tool(client, name, args);
        expect(result.isError).toBeUndefined();
        expect(body).toEqual({ result: expected, metadata: {} });
        expect(result.structuredContent).toEqual(body);
        expect(JSON.stringify(result)).not.toContain(sentinel);
      }
      expect(fake.seen("GET", orderPath)).toHaveLength(1);
      expect(fake.seen("GET", "/api/v1/commissions")).toHaveLength(1);
      for (const request of fake.seen("GET")) {
        expect(request.body).toBe("");
        expect(request.query.toString()).toBe("");
        expect(request.headers.get("x-tossinvest-account")).toBe(
          "9223372036854775807",
        );
        expect(request.headers.get("authorization")).toBe(
          "Bearer fake-access-token-1",
        );
      }
      const accountNo = "12345678901";
      fake.routes.set(
        "GET /api/v1/accounts",
        new Response(
          '{"result":[{"accountSeq":9223372036854775807,"accountNo":"12345678901","accountType":"BROKERAGE"}]}',
          { headers: { "content-type": "application/json" } },
        ),
      );
      const accounts = await tool(client, "list_accounts", {});
      expect(accounts.result.isError).toBeUndefined();
      expect(accounts.body.result).toMatchObject([
        { accountSeq: "9223372036854775807" },
      ]);
      expect(accounts.body.accounts).toMatchObject([
        {
          account_seq: "9223372036854775807",
          label: expect.stringContaining("****8901"),
        },
      ]);

      expect(JSON.stringify(accounts.result)).not.toContain(accountNo);
      expect(fake.seen("GET", "/api/v1/accounts")).toHaveLength(1);
      expect(
        fake
          .seen("GET", "/api/v1/accounts")[0]!
          .headers.get("x-tossinvest-account"),
      ).toBeNull();
      expect(fake.seen("GET")).toHaveLength(3);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      expect(
        fake.requests.filter(
          (row) => row.method === "POST" && row.path !== "/oauth2/token",
        ),
      ).toHaveLength(0);
      expect(fake.seen("DELETE")).toHaveLength(0);
      console.log(
        `${mode}: tools=8 operations=10 invalidHTTP=0 authPOST=1 getOrder=1 getCommissions=1 getAccounts=1 financialPOST=0 DELETE=0`,
      );
    } finally {
      await client.close();
      fake.stop();
      await rm(directory, { recursive: true, force: true });
    }
  });
}
