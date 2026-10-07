import { ToolError } from "./errors.ts";

/** Bun 의 fetch 와 WebSocket 이 읽는 프록시 env. CDP 주소는 사설 주소라 프록시를 거치면 닿지 않는다. */
export const PROXY_ENVIRONMENT_KEYS = [
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "ALL_PROXY",
  "http_proxy",
  "https_proxy",
  "all_proxy",
] as const;

const HTTP_TIMEOUT_MS = 5_000;
const COMMAND_TIMEOUT_MS = 30_000;
/** Chrome 허용 목록과 맞추도록 CDP 주소의 포트로 loopback HTTP Origin 을 만든다. */
export function websocketOriginFor(cdpUrl: string) {
  // HTTP Origin 의 기본 포트 규칙을 적용하고, HTTPS 주소에 명시한 포트도 보존한다.
  const origin = new URL(cdpUrl.replace(/^https:/, "http:"));
  origin.hostname = "localhost";
  return origin.origin;
}

/** 테스트처럼 이미 실행 중인 프로세스에서도 다음 요청이 프록시 값을 읽지 않게 한다. */
export function clearProxyEnvironment() {
  for (const key of PROXY_ENVIRONMENT_KEYS) delete process.env[key];
}

const unreachable = () => new ToolError("NAVER_BLOG_BROWSER_UNREACHABLE");

/** 끝 `/` 를 뗀 CDP 주소. */
export function endpoint(cdpUrl: string) {
  return cdpUrl.replace(/\/+$/, "");
}

/**
 * Chrome 디버깅 HTTP 창구(`/json/version`, `/json/list`, `/json/new?<주소>`, `/json/close/<id>`)를 부른다.
 * JSON 이 아닌 답(`/json/close` 의 글)은 글 그대로 돌려준다. 닿지 않거나 2xx 가 아니면 원문 없이 실패한다.
 */
export async function httpJson(
  cdpUrl: string,
  path: string,
  {
    method = "GET",
    timeoutMs = HTTP_TIMEOUT_MS,
  }: { method?: string; timeoutMs?: number } = {},
): Promise<unknown> {
  clearProxyEnvironment();
  let text: string;
  try {
    const response = await fetch(`${endpoint(cdpUrl)}${path}`, {
      method,
      signal: AbortSignal.timeout(Math.max(1, timeoutMs)),
      redirect: "error",
    });
    if (!response.ok) throw unreachable();
    text = await response.text();
  } catch {
    throw unreachable();
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/**
 * Chrome 이 준 `webSocketDebuggerUrl` 의 경로만 꺼내 `cdp_url` 의 호스트와 포트에 붙인다.
 * Chrome 은 자기 loopback 주소를 적으므로 중계 너머에서는 그 호스트로 닿지 않는다.
 */
export function wsUrlFor(cdpUrl: string, webSocketDebuggerUrl: string) {
  let base: URL;
  let debuggerPath: string;
  try {
    base = new URL(endpoint(cdpUrl));
    debuggerPath = new URL(webSocketDebuggerUrl).pathname;
  } catch {
    throw unreachable();
  }
  if (!debuggerPath.startsWith("/devtools/")) throw unreachable();
  const scheme = base.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${base.host}${debuggerPath}`;
}

type Pending = {
  resolve: (value: any) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

/** CDP WebSocket 한 줄. 명령은 응답의 `id` 로 짝을 맞추고, 명령과 이벤트 대기마다 제한 시간을 둔다. */
export class CdpSession {
  private nextId = 1;
  private closed = false;
  private readonly pending = new Map<number, Pending>();
  private readonly waiters = new Map<string, Set<Pending>>();
  private readonly listeners = new Map<string, Set<(params: any) => void>>();

  private constructor(private readonly socket: WebSocket) {
    socket.addEventListener("message", (event) => this.receive(event.data));
    socket.addEventListener("close", () => this.shutdown());
    socket.addEventListener("error", () => this.shutdown());
  }

  /** 대상 하나에 붙는다. 브라우저 대상에는 Page 도메인이 없어 붙자마자 아무것도 켜지 않는다. */
  static connect(wsUrl: string, cdpUrl: string, timeoutMs = COMMAND_TIMEOUT_MS) {
    clearProxyEnvironment();
    return new Promise<CdpSession>((resolve, reject) => {
      let socket: WebSocket;
      try {
        // Bun 의 WebSocket 은 두 번째 인자로 headers 를 받는다. tsconfig 의 lib 에서 DOM 을 빼 bun-types 선언을 쓴다.
        socket = new WebSocket(wsUrl, {
          headers: { Origin: websocketOriginFor(cdpUrl) },
        });
      } catch {
        reject(unreachable());
        return;
      }
      const timer = setTimeout(() => {
        socket.close();
        reject(unreachable());
      }, Math.max(1, timeoutMs));
      const fail = () => {
        clearTimeout(timer);
        reject(unreachable());
      };
      socket.addEventListener("error", fail, { once: true });
      socket.addEventListener("close", fail, { once: true });
      socket.addEventListener(
        "open",
        () => {
          clearTimeout(timer);
          socket.removeEventListener("error", fail);
          socket.removeEventListener("close", fail);
          resolve(new CdpSession(socket));
        },
        { once: true },
      );
    });
  }

  /** 명령을 보내고 같은 `id` 의 응답을 기다린다. 브라우저가 준 오류 원문은 버린다. */
  send<T = any>(
    method: string,
    params: Record<string, unknown> = {},
    timeoutMs = COMMAND_TIMEOUT_MS,
  ): Promise<T> {
    if (this.closed) return Promise.reject(unreachable());
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(unreachable());
      }, Math.max(1, timeoutMs));
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.socket.send(JSON.stringify({ id, method, params }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(unreachable());
      }
    });
  }

  /** 이름이 같은 이벤트 하나를 기다려 그 `params` 를 돌려준다. */
  waitEvent<T = any>(method: string, timeoutMs = COMMAND_TIMEOUT_MS): Promise<T> {
    if (this.closed) return Promise.reject(unreachable());
    return new Promise<T>((resolve, reject) => {
      const waiters = this.waiters.get(method) ?? new Set<Pending>();
      this.waiters.set(method, waiters);
      const waiter: Pending = {
        resolve,
        reject,
        timer: setTimeout(() => {
          waiters.delete(waiter);
          reject(unreachable());
        }, Math.max(1, timeoutMs)),
      };
      waiters.add(waiter);
    });
  }

  /**
   * 이름이 같은 이벤트가 올 때마다 부를 함수를 건다. 돌려준 함수를 부르면 뗀다.
   * 탭에 뜬 대화 상자처럼 언제 올지 모르는 이벤트를 연결 내내 받을 때 쓴다.
   */
  onEvent(method: string, listener: (params: any) => void) {
    const listeners = this.listeners.get(method) ?? new Set();
    this.listeners.set(method, listeners);
    listeners.add(listener);
    return () => listeners.delete(listener);
  }

  close() {
    this.shutdown();
    try {
      this.socket.close();
    } catch {
      // 이미 닫힌 연결은 다시 닫을 것이 없다.
    }
  }

  private receive(data: unknown) {
    let message: any;
    try {
      message = JSON.parse(String(data));
    } catch {
      return;
    }
    if (typeof message?.id === "number") {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) pending.reject(new ToolError("NAVER_BLOG_UNAVAILABLE"));
      else pending.resolve(message.result ?? {});
      return;
    }
    if (typeof message?.method === "string") {
      for (const listener of this.listeners.get(message.method) ?? []) {
        try {
          listener(message.params ?? {});
        } catch {
          // 받는 쪽의 실패가 다른 이벤트와 명령 응답을 막지 않게 한다.
        }
      }
      const waiters = this.waiters.get(message.method);
      if (!waiters) return;
      for (const waiter of waiters) {
        clearTimeout(waiter.timer);
        waiter.resolve(message.params ?? {});
      }
      this.waiters.delete(message.method);
    }
  }

  /** 연결이 끊기면 기다리던 명령과 이벤트를 모두 닿지 않음으로 끝낸다. */
  private shutdown() {
    if (this.closed) return;
    this.closed = true;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(unreachable());
    }
    this.pending.clear();
    for (const waiters of this.waiters.values()) {
      for (const waiter of waiters) {
        clearTimeout(waiter.timer);
        waiter.reject(unreachable());
      }
    }
    this.waiters.clear();
    this.listeners.clear();
  }
}
