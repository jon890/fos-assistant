import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import manifest from "../connector.json";
import { baseUrlOf, createWorkbenchServer } from "../src/server.ts";
import {
  credentials,
  expectFailure,
  FakeWorkbench,
  json,
  nodeId,
  token,
  tool,
  upstreamText,
  withMcp,
} from "./support.ts";

let fake: FakeWorkbench;
beforeEach(() => {
  fake = new FakeWorkbench();
});
afterEach(() => fake.stop());

const server = (env: Record<string, string> = credentials) =>
  createWorkbenchServer({ baseUrl: fake.url, env, timeoutMs: 500 });

const me = {
  user: { id: nodeId, displayName: "Alice", role: "EDITOR" },
  token: { id: nodeId, name: "Hermes", last4: "test" },
  rateLimitPerMinute: 60,
};

describe("도구 목록", () => {
  test("서버의 도구와 manifest 의 선언이 같고 모두 읽기 전용이다", async () => {
    await withMcp(server(), async (client) => {
      const tools = (await client.listTools()).tools;
      expect(tools.map((item) => item.name).sort()).toEqual(
        Object.keys(manifest.tools).sort(),
      );
      for (const item of tools)
        expect(item.annotations?.readOnlyHint).toBe(true);
    });
  });
});

describe("요청 경로와 인증", () => {
  test("연결 확인은 /me 를 Bearer 토큰으로 부르고 이름만 돌려준다", async () => {
    fake.on("/api/v1/me", { ...me, extra: "ignored" });
    await withMcp(server(), async (client) => {
      const { result, body } = await tool(client, "get_connection");
      expect(result.isError).toBeFalsy();
      expect(body).toEqual(me);
    });
    const [request] = fake.seen("/api/v1/me");
    expect(request!.headers.get("authorization")).toBe(`Bearer ${token}`);
    expect(request!.headers.get("accept")).toBe("application/json");
  });

  test("도구마다 계약의 경로와 쿼리를 부른다", async () => {
    const cases: Array<
      [string, Record<string, unknown>, string, Record<string, string>]
    > = [
      ["get_ontology", {}, "/api/v1/ontology", {}],
      ["search_nodes", {}, "/api/v1/nodes", { page: "0", size: "20" }],
      [
        "search_nodes",
        { q: " FLNC ", types: "Company, Decision,", page: 1, size: 50 },
        "/api/v1/nodes",
        { q: "FLNC", types: "Company,Decision", page: "1", size: "50" },
      ],
      ["get_node", { id: nodeId }, `/api/v1/nodes/${nodeId}`, {}],
      ["trace_node", { id: nodeId }, `/api/v1/nodes/${nodeId}/trace`, {}],
      [
        "get_neighborhood",
        { id: nodeId },
        "/api/v1/graph/neighborhood",
        { id: nodeId, hops: "1" },
      ],
      [
        "get_neighborhood",
        { id: nodeId, hops: 2 },
        "/api/v1/graph/neighborhood",
        { id: nodeId, hops: "2" },
      ],
      [
        "lookup_security",
        { ticker: "FLNC" },
        "/api/v1/securities/lookup",
        { ticker: "FLNC" },
      ],
      [
        "lookup_security",
        { ticker: "005930", market: "kr" },
        "/api/v1/securities/lookup",
        { ticker: "005930", market: "KR" },
      ],
      [
        "lookup_security",
        { ticker: "BRK.B", market: "NYSE" },
        "/api/v1/securities/lookup",
        { ticker: "BRK.B", market: "NYSE" },
      ],
      [
        "list_my_knowledge",
        {},
        "/api/v1/me/knowledge",
        { page: "0", size: "20" },
      ],
      [
        "list_my_knowledge",
        { level: "unsure,LEARNING", size: 5 },
        "/api/v1/me/knowledge",
        { level: "UNSURE,LEARNING", page: "0", size: "5" },
      ],
    ];
    for (const [, , path] of cases) fake.on(path, { ok: path });
    await withMcp(server(), async (client) => {
      for (const [name, args, path, query] of cases) {
        const before = fake.requests.length;
        const { result, body } = await tool(client, name, args);
        expect(result.isError).toBeFalsy();
        expect(body).toEqual({ ok: path });
        const request = fake.requests[before]!;
        expect(fake.requests.length).toBe(before + 1);
        expect(request.path).toBe(path);
        expect(Object.fromEntries(request.query)).toEqual(query);
        expect(request.headers.get("authorization")).toBe(`Bearer ${token}`);
      }
    });
  });

  test("운영자가 준 기준 주소의 경로 아래에 /api/v1 을 붙인다", async () => {
    fake.on("/prefix/api/v1/me", me);
    await withMcp(
      createWorkbenchServer({
        baseUrl: `${fake.url}/prefix`,
        env: credentials,
      }),
      async (client) => {
        expect((await tool(client, "get_connection")).body).toEqual(me);
      },
    );
  });
});

describe("오류", () => {
  test("401 은 다시 연결하라는 코드로 끝나고 서비스의 글을 싣지 않는다", async () => {
    fake.on(
      "/api/v1/me",
      { code: "UNAUTHENTICATED", message: upstreamText },
      401,
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_UNAUTHORIZED" },
        "get_connection",
      );
    });
  });

  test("토큰이 없거나 형식이 틀리면 부르지 않고 같은 코드로 끝난다", async () => {
    const envs: Record<string, string>[] = [
      {},
      { FINANCIAL_WORKBENCH_TOKEN: "fwb_short" },
    ];
    for (const env of envs) {
      await withMcp(server(env), async (client) => {
        await expectFailure(
          client,
          { code: "WORKBENCH_UNAUTHORIZED" },
          "get_ontology",
        );
      });
    }
    expect(fake.requests).toHaveLength(0);
  });

  test("429 는 Retry-After 초를 알리고 다시 부르지 않는다", async () => {
    fake.on(
      "/api/v1/ontology",
      {
        code: "RATE_LIMITED",
        message: upstreamText,
        details: { retryAfterSeconds: 41 },
      },
      429,
      { "retry-after": "42" },
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_RATE_LIMITED", retry_after_seconds: 42 },
        "get_ontology",
      );
    });
    expect(fake.seen("/api/v1/ontology")).toHaveLength(1);
  });

  test("429 의 Retry-After 가 없으면 본문의 초를, 둘 다 없으면 코드만 낸다", async () => {
    fake.on(
      "/api/v1/ontology",
      { code: "RATE_LIMITED", details: { retryAfterSeconds: 7 } },
      429,
    );
    fake.on("/api/v1/me", { code: "RATE_LIMITED" }, 429);
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_RATE_LIMITED", retry_after_seconds: 7 },
        "get_ontology",
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_RATE_LIMITED" },
        "get_connection",
      );
    });
  });

  test("상태 코드마다 정해진 코드로 끝난다", async () => {
    const cases: Array<[number, string]> = [
      [400, "WORKBENCH_INVALID_INPUT"],
      [403, "WORKBENCH_FORBIDDEN"],
      [404, "WORKBENCH_NOT_FOUND"],
      [405, "WORKBENCH_UNAVAILABLE"],
      [500, "WORKBENCH_UNAVAILABLE"],
      [503, "WORKBENCH_UNAVAILABLE"],
    ];
    await withMcp(server(), async (client) => {
      for (const [status, code] of cases) {
        fake.on(
          `/api/v1/nodes/${nodeId}`,
          { code: "X", message: upstreamText },
          status,
        );
        await expectFailure(client, { code }, "get_node", { id: nodeId });
      }
    });
  });

  test("다른 곳으로 넘기는 응답을 따라가지 않는다", async () => {
    fake.routes.set(
      "GET /api/v1/me",
      new Response(null, {
        status: 302,
        headers: { location: `${fake.url}/api/v1/ontology` },
      }),
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_UNAVAILABLE" },
        "get_connection",
      );
    });
    expect(fake.seen("/api/v1/ontology")).toHaveLength(0);
  });

  test("시간 안에 답하지 않거나 JSON 객체가 아니면 서비스를 쓸 수 없다고 끝난다", async () => {
    fake.routes.set(
      "GET /api/v1/me",
      () =>
        new Promise<Response>((resolve) =>
          setTimeout(() => resolve(json(me)), 2_000),
        ),
    );
    fake.routes.set(
      "GET /api/v1/ontology",
      new Response("not json", { status: 200 }),
    );
    await withMcp(server(), async (client) => {
      await expectFailure(
        client,
        { code: "WORKBENCH_UNAVAILABLE" },
        "get_connection",
      );
      await expectFailure(
        client,
        { code: "WORKBENCH_UNAVAILABLE" },
        "get_ontology",
      );
    });
  });

  test("계약 밖의 인자는 부르기 전에 거절한다", async () => {
    const cases: Array<[string, Record<string, unknown>]> = [
      ["get_node", { id: "../me" }],
      ["trace_node", { id: `${nodeId}/x` }],
      ["get_neighborhood", { id: nodeId, hops: 3 }],
      ["get_neighborhood", { id: "not-a-uuid" }],
      ["search_nodes", { q: "가".repeat(101) }],
      ["search_nodes", { types: "Company;DROP" }],
      ["search_nodes", { size: 51 }],
      ["search_nodes", { page: -1 }],
      ["lookup_security", { ticker: "" }],
      ["lookup_security", { ticker: "A".repeat(33) }],
      ["lookup_security", { ticker: "FLNC", market: "LSE" }],
      ["list_my_knowledge", { level: "KNOWN,FORGOT" }],
      ["list_my_knowledge", { size: 0 }],
    ];
    await withMcp(server(), async (client) => {
      for (const [name, args] of cases)
        await expectFailure(
          client,
          { code: "WORKBENCH_INVALID_INPUT" },
          name,
          args,
        );
    });
    expect(fake.requests).toHaveLength(0);
  });
});

describe("결과", () => {
  test("워크벤치의 잘림 표시를 그대로 전한다", async () => {
    const lookup = {
      market: "US",
      ticker: "FLNC",
      matches: [
        {
          security: { id: nodeId, label: "Fluence Energy 보통주" },
          issuer: null,
          decisions: [],
          valuations: [
            { node: { id: nodeId }, scenarios: [], scenariosTruncated: true },
          ],
          decisionsTruncated: true,
          valuationsTruncated: false,
        },
      ],
      matchesTruncated: true,
    };
    const detail = {
      node: { id: nodeId },
      relations: [],
      relationsTruncated: true,
      claimsTruncated: false,
    };
    fake.on("/api/v1/securities/lookup", lookup);
    fake.on(`/api/v1/nodes/${nodeId}`, detail);
    fake.on("/api/v1/graph/neighborhood", {
      nodes: [],
      edges: [],
      truncated: true,
    });
    await withMcp(server(), async (client) => {
      expect(
        (
          await tool(client, "lookup_security", {
            ticker: "FLNC",
            market: "US",
          })
        ).body,
      ).toEqual(lookup);
      expect((await tool(client, "get_node", { id: nodeId })).body).toEqual(
        detail,
      );
      expect(
        (await tool(client, "get_neighborhood", { id: nodeId })).body.truncated,
      ).toBe(true);
    });
  });

  test("아주 긴 글은 잘라 끝에 표시하고 textTruncated 를 참으로 둔다", async () => {
    fake.on(`/api/v1/nodes/${nodeId}`, {
      properties: [
        { key: "body", value: "가".repeat(20_005) },
        { key: "short", value: "짧다" },
      ],
    });
    await withMcp(server(), async (client) => {
      const { body } = await tool(client, "get_node", { id: nodeId });
      expect(body.textTruncated).toBe(true);
      expect(Array.from(body.properties[0].value as string)).toHaveLength(
        20_001,
      );
      expect((body.properties[0].value as string).endsWith("…")).toBe(true);
      expect(body.properties[1].value).toBe("짧다");
    });
  });
});

describe("기준 주소", () => {
  test("https 주소만 받고 끝의 / 를 지운다", () => {
    expect(baseUrlOf("https://workbench.example")).toBe(
      "https://workbench.example",
    );
    expect(baseUrlOf(" https://workbench.example/sub/ ")).toBe(
      "https://workbench.example/sub",
    );
    for (const wrong of [
      undefined,
      "",
      "placeholder",
      "http://workbench.example",
      "https://user:pass@workbench.example",
      "https://workbench.example/?a=1",
      "https://workbench.example/#x",
      "https://workbench.example/?",
      "file:///etc/passwd",
    ])
      expect(() => baseUrlOf(wrong)).toThrow("WORKBENCH_NOT_CONFIGURED");
  });

  test("운영 값이 없거나 틀리면 부르지 않고 설정되지 않았다고 끝난다", async () => {
    for (const base of [undefined, "http://127.0.0.1:1"]) {
      const env: Record<string, string> = { ...credentials };
      if (base) env.FINANCIAL_WORKBENCH_BASE_URL = base;
      await withMcp(createWorkbenchServer({ env }), async (client) => {
        await expectFailure(
          client,
          { code: "WORKBENCH_NOT_CONFIGURED" },
          "get_connection",
        );
      });
    }
  });
});
