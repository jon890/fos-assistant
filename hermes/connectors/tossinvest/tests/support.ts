import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { expect } from "bun:test";

/** 모두 지어낸 값이다. 실제 계정이나 사람과 관계가 없다. */
export const credentials = {
  TOSSINVEST_CLIENT_ID: "fake-client-id-for-tests",
  TOSSINVEST_CLIENT_SECRET: "fake-client-secret-for-tests",
};
export const accountNo = "12345678901";
export const tokenPrefix = "fake-access-token-";
export const upstreamText = "upstream-error-text-for-tests";

export type RecordedRequest = {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: string;
};

export type Route =
  | Response
  | ((request: RecordedRequest) => Response | Promise<Response>);

/** 실제 토스증권 대신 도구가 만든 HTTP 요청만 받는 짧게 사는 대역이다. */
export class FakeToss {
  readonly requests: RecordedRequest[] = [];
  readonly routes = new Map<string, Route>();
  readonly server: ReturnType<typeof Bun.serve>;
  /** 발급할 때마다 번호를 올려 다시 받은 토큰을 구별한다. */
  issued = 0;
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
          body: await request.text(),
        };
        this.requests.push(recorded);
        if (!isAllowed(recorded.method, recorded.path)) this.allowed = false;
        const route = this.routes.get(`${recorded.method} ${recorded.path}`);
        if (!route)
          return json(
            { error: { requestId: "r", code: "not-found", message: upstreamText } },
            404,
          );
        return route instanceof Response ? route.clone() : route(recorded);
      },
    });
    this.routes.set("POST /oauth2/token", () => {
      this.issued += 1;
      return json({
        access_token: `${tokenPrefix}${this.issued}`,
        token_type: "Bearer",
        expires_in: 3600,
      });
    });
  }

  get url() {
    return this.server.url.toString().replace(/\/$/, "");
  }

  on(method: string, path: string, body: unknown, status = 200) {
    this.routes.set(`${method} ${path}`, json(body, status));
  }

  /** API 오류 본문이다. `message` 는 결과 어디에도 나오면 안 되는 서비스의 글이다. */
  fail(method: string, path: string, status: number, code: string) {
    this.on(
      method,
      path,
      { error: { requestId: "req-1", code, message: upstreamText } },
      status,
    );
  }

  /** 받은 순서대로 다른 응답을 준다. 목록이 끝나면 마지막 것을 되풀이한다. */
  sequence(method: string, path: string, responses: Array<() => Response>) {
    let index = 0;
    this.routes.set(`${method} ${path}`, () => {
      const make = responses[Math.min(index, responses.length - 1)]!;
      index += 1;
      return make();
    });
  }

  seen(method?: string, path?: string) {
    return this.requests.filter(
      (request) =>
        (!method || request.method === method) &&
        (!path || request.path === path),
    );
  }

  stop() {
    this.server.stop(true);
    expect(this.allowed).toBe(true);
  }
}

/** 도구가 계약 밖의 경로(주문 등)를 부르면 모든 시험이 실패한다. */
function isAllowed(method: string, path: string) {
  return new Set([
    "POST /oauth2/token",
    "GET /api/v1/accounts",
    "GET /api/v1/prices",
    "GET /api/v1/stocks",
    "GET /api/v1/holdings",
    "GET /api/v1/buying-power",
    "GET /api/v1/sellable-quantity",
    "GET /api/v1/orders",
  ]).has(`${method} ${path}`);
}

export function json(body: unknown, status = 200, headers?: HeadersInit) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

export const apiError = (status: number, code: string) => () =>
  json({ error: { requestId: "req-2", code, message: upstreamText } }, status);

export async function withMcp(
  server: McpServer,
  work: (client: Client) => Promise<void>,
) {
  const client = new Client({ name: "tossinvest-test", version: "1.0.0" });
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

/** 도구를 부르고, 결과 글에 secret, 토큰, 계좌번호 원문, 서비스의 message 가 없는지 본다. */
export async function tool(
  client: Client,
  name: string,
  arguments_: Record<string, unknown> = {},
) {
  const result = await client.callTool({ name, arguments: arguments_ });
  const text = (result.content as Array<{ text: string }>)[0]?.text ?? "";
  for (const secret of Object.values(credentials)) {
    expect(text).not.toContain(secret);
  }
  expect(text).not.toContain(tokenPrefix);
  expect(text).not.toContain(accountNo);
  expect(text).not.toContain(upstreamText);
  return { result, body: JSON.parse(text) as Record<string, unknown> };
}

export async function expectFailure(
  client: Client,
  code: string,
  name: string,
  arguments_: Record<string, unknown> = {},
) {
  const { result, body } = await tool(client, name, arguments_);
  expect(result.isError).toBe(true);
  expect(body).toEqual({ error: { code } });
}
