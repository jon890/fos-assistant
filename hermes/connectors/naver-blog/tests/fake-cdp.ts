import type { ServerWebSocket } from "bun";

/** 브라우저가 준 오류 원문. 결과와 오류 글에 나오면 안 된다. */
export const upstreamText = "upstream-error-text-for-tests";

export type CdpCall = { target: string; method: string; params: any };
export type CdpHandler = (params: any, target: string) => unknown;

type SocketData = { target: string };

/**
 * 실제 Chrome 대신 디버깅 HTTP 창구와 WebSocket 명령에 답하는 대역이다.
 * 받은 CDP 메서드를 기록하고, 허용 목록 밖의 메서드는 위반으로 남긴다.
 */
export class FakeCdp {
  readonly calls: CdpCall[] = [];
  readonly httpRequests: string[] = [];
  readonly origins: string[] = [];
  readonly violations: string[] = [];
  /** `/json/version` 이 돌려줄 `webSocketDebuggerUrl`. 비우면 이 대역의 주소를 쓴다. */
  debuggerUrl?: string;
  /** 참이면 HTTP 요청에 답하지 않는다. */
  hangHttp = false;
  private readonly handlers = new Map<string, CdpHandler>();
  private readonly allowed: Set<string>;
  private readonly targets = new Map<string, string>();
  private nextTarget = 1;
  readonly server: ReturnType<typeof Bun.serve<SocketData, never>>;

  constructor({ allowed = ["Storage.getCookies"] }: { allowed?: string[] } = {}) {
    this.allowed = new Set(allowed);
    this.server = Bun.serve<SocketData, never>({
      hostname: "127.0.0.1",
      port: 0,
      fetch: (request, server) => this.http(request, server),
      websocket: {
        message: (socket, message) => this.command(socket, String(message)),
      },
    });
  }

  /** 시험이 연결 칸에 넣을 주소. 포트는 시험마다 운영체제가 고른다. */
  get url() {
    return `http://${this.server.hostname}:${this.server.port}`;
  }

  /** 메서드마다 답을 정한다. 처리 함수가 던지면 CDP 오류로 답한다. */
  on(method: string, handler: CdpHandler) {
    this.handlers.set(method, handler);
  }

  methods() {
    return this.calls.map((call) => call.method);
  }

  stop() {
    this.server.stop(true);
  }

  private http(request: Request, server: Bun.Server<SocketData>) {
    const url = new URL(request.url);
    this.httpRequests.push(`${request.method} ${url.pathname}`);
    const socketPath = /^\/devtools\/(browser|page)\/([A-Za-z0-9-]+)$/.exec(
      url.pathname,
    );
    if (socketPath) {
      this.origins.push(request.headers.get("origin") ?? "");
      if (server.upgrade(request, { data: { target: `${socketPath[1]}/${socketPath[2]}` } }))
        return undefined;
      return new Response("upgrade failed", { status: 400 });
    }
    if (this.hangHttp) return new Promise<Response>(() => {});
    const base = `ws://${this.server.hostname}:${this.server.port}`;
    if (request.method === "GET" && url.pathname === "/json/version")
      return Response.json({
        Browser: "Chrome/0.0.0.0",
        webSocketDebuggerUrl:
          this.debuggerUrl ?? `${base}/devtools/browser/fake-browser`,
      });
    if (request.method === "GET" && url.pathname === "/json/list")
      return Response.json(
        [...this.targets].map(([id, page]) => ({
          id,
          type: "page",
          url: page,
          webSocketDebuggerUrl: `${base}/devtools/page/${id}`,
        })),
      );
    if (request.method === "PUT" && url.pathname === "/json/new") {
      const id = `page-${this.nextTarget++}`;
      const page = decodeURIComponent(url.search.slice(1));
      this.targets.set(id, page);
      return Response.json({
        id,
        type: "page",
        url: page,
        webSocketDebuggerUrl: `${base}/devtools/page/${id}`,
      });
    }
    const close = /^\/json\/close\/([A-Za-z0-9-]+)$/.exec(url.pathname);
    if (request.method === "GET" && close) {
      this.targets.delete(close[1]!);
      return new Response("Target is closing");
    }
    this.violations.push(`HTTP ${request.method} ${url.pathname}`);
    return new Response(upstreamText, { status: 404 });
  }

  private command(socket: ServerWebSocket<SocketData>, raw: string) {
    const message = JSON.parse(raw) as { id: number; method: string; params?: any };
    const target = socket.data.target;
    this.calls.push({ target, method: message.method, params: message.params ?? {} });
    const reply = (body: Record<string, unknown>) =>
      socket.send(JSON.stringify({ id: message.id, ...body }));
    if (!this.allowed.has(message.method)) {
      this.violations.push(`CDP ${message.method}`);
      reply({ error: { code: -32601, message: upstreamText } });
      return;
    }
    const handler = this.handlers.get(message.method);
    // 답을 정하지 않은 메서드는 답하지 않는다. 시험이 시간 초과를 이렇게 만든다.
    if (!handler) return;
    try {
      reply({ result: handler(message.params ?? {}, target) ?? {} });
    } catch {
      reply({ error: { code: -32000, message: upstreamText } });
    }
  }
}

/** 이미 닫힌 포트의 주소. 띄웠다 닫아 운영체제가 고른 포트를 쓴다. */
export function closedPortUrl() {
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: () => new Response("") });
  const url = `http://${server.hostname}:${server.port}`;
  server.stop(true);
  return url;
}
