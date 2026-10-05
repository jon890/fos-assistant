import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { expect } from "bun:test";

export const credentials = {
  GMAIL_OAUTH_CLIENT_ID: "fake-client-for-tests.apps.googleusercontent.com",
  GMAIL_OAUTH_CLIENT_SECRET: "fake-client-secret-for-tests",
  GMAIL_OAUTH_REFRESH_TOKEN: "fake-refresh-token-for-tests",
};

export const accessToken = "fake-access-token-for-tests";
export const upstreamText = "upstream-error-text-for-tests";

export type RecordedRequest = {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: string;
};

export type Route =
  Response | ((request: RecordedRequest) => Response | Promise<Response>);

/** 실제 Google 대신 도구가 만든 HTTP 요청만 받는 짧게 사는 대역이다. */
export class FakeGoogle {
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
          body: await request.text(),
        };
        this.requests.push(recorded);
        if (!isAllowed(recorded.method, recorded.path)) this.allowed = false;
        const route = this.routes.get(`${recorded.method} ${recorded.path}`);
        if (!route) return json({ error: { message: upstreamText } }, 404);
        return route instanceof Response ? route.clone() : route(recorded);
      },
    });
  }

  get url() {
    return this.server.url.toString().replace(/\/$/, "");
  }

  on(method: string, path: string, body: unknown, status = 200) {
    this.routes.set(`${method} ${path}`, json(body, status));
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

/** Gmail 도구가 계약 밖의 삭제·휴지통·첨부 endpoint를 부르면 모든 시험이 실패한다. */
function isAllowed(method: string, path: string) {
  const exact = new Set([
    "POST /token",
    "GET /gmail/profile",
    "GET /gmail/labels",
    "POST /gmail/labels",
    "GET /gmail/messages",
    "POST /gmail/messages/send",
    "POST /gmail/messages/batchModify",
    "GET /gmail/settings/filters",
    "POST /gmail/settings/filters",
    "POST /gmail/drafts",
  ]);
  if (exact.has(`${method} ${path}`)) return true;
  return (
    /^GET \/gmail\/(?:messages|threads)\/[A-Za-z0-9_-]{1,64}$/.test(
      `${method} ${path}`,
    ) ||
    /^POST \/gmail\/messages\/[A-Za-z0-9_-]{1,64}\/modify$/.test(
      `${method} ${path}`,
    ) ||
    /^PATCH \/gmail\/labels\/[A-Za-z0-9_-]{1,64}$/.test(`${method} ${path}`) ||
    /^DELETE \/gmail\/settings\/filters\/[A-Za-z0-9_-]{1,64}$/.test(
      `${method} ${path}`,
    )
  );
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
  const client = new Client({ name: "gmail-test", version: "1.0.0" });
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
  for (const secret of Object.values(credentials)) {
    expect(text).not.toContain(secret);
  }
  expect(text).not.toContain(accessToken);
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
