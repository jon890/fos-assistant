import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { expect } from "bun:test";

// 형식만 맞춘 가짜 토큰이다. 실제 워크벤치가 발급한 값이 아니다.
export const token = `fwb_${"A".repeat(39)}test`;
export const credentials = { FINANCIAL_WORKBENCH_TOKEN: token };
export const upstreamText = "upstream-error-text-for-tests";
export const nodeId = "0b8f3c2e-5d1a-4c7b-9e2f-1a2b3c4d5e6f";

export type RecordedRequest = {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
};

export type Route =
  Response | ((request: RecordedRequest) => Response | Promise<Response>);

/** 실제 워크벤치 대신 도구가 만든 HTTP 요청만 받는 짧게 사는 대역이다. */
export class FakeWorkbench {
  readonly requests: RecordedRequest[] = [];
  readonly routes = new Map<string, Route>();
  readonly server: ReturnType<typeof Bun.serve>;
  private allowed = true;

  constructor() {
    this.server = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      fetch: async (request) => {
        const url = new URL(request.url);
        const recorded = {
          method: request.method,
          path: url.pathname,
          query: url.searchParams,
          headers: request.headers,
        };
        this.requests.push(recorded);
        if (!isAllowed(recorded.method, recorded.path)) this.allowed = false;
        const route = this.routes.get(`${recorded.method} ${recorded.path}`);
        if (!route)
          return json({ code: "NOT_FOUND", message: upstreamText }, 404);
        return route instanceof Response ? route.clone() : route(recorded);
      },
    });
  }

  get url() {
    return this.server.url.toString().replace(/\/$/, "");
  }

  on(path: string, body: unknown, status = 200, headers?: HeadersInit) {
    this.routes.set(`GET ${path}`, json(body, status, headers));
  }

  seen(path?: string) {
    return this.requests.filter((request) => !path || request.path === path);
  }

  stop() {
    this.server.stop(true);
    expect(this.allowed).toBe(true);
  }
}

/** 계약의 `/api/v1` 조회 경로만 허용한다. 목록 밖의 메서드나 경로를 부르면 그 테스트가 실패한다. */
function isAllowed(method: string, requested: string) {
  if (method !== "GET") return false;
  // 기준 주소에 경로가 붙은 경우를 보는 테스트는 `/prefix` 를 쓴다.
  const path = requested.replace(/^\/prefix(?=\/api\/v1\/)/, "");
  const exact = new Set([
    "/api/v1/me",
    "/api/v1/ontology",
    "/api/v1/nodes",
    "/api/v1/graph/neighborhood",
    "/api/v1/securities/lookup",
    "/api/v1/me/knowledge",
  ]);
  if (exact.has(path)) return true;
  return /^\/api\/v1\/nodes\/[0-9a-fA-F-]{36}(?:\/trace)?$/.test(path);
}

export function json(body: unknown, status = 200, headers?: HeadersInit) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

export async function withMcp(
  server: McpServer,
  work: (client: Client) => Promise<void>,
) {
  const client = new Client({ name: "workbench-test", version: "1.0.0" });
  const [clientTransport, serverTransport] =
    InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    await work(client);
  } finally {
    await client.close();
    await server.close();
  }
}

export async function tool(
  client: Client,
  name: string,
  arguments_: Record<string, unknown> = {},
) {
  const result = await client.callTool({ name, arguments: arguments_ });
  const text = (result.content as Array<{ text: string }>)[0]?.text ?? "";
  expect(text).not.toContain(token);
  expect(text).not.toContain(upstreamText);
  return { result, body: JSON.parse(text) as Record<string, any> };
}

export async function expectFailure(
  client: Client,
  error: Record<string, unknown>,
  name: string,
  arguments_: Record<string, unknown> = {},
) {
  const { result, body } = await tool(client, name, arguments_);
  expect(result.isError).toBe(true);
  expect(body).toEqual({ error });
}
