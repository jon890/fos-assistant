import { CareerError, configuredValue } from "../errors.ts";

// fos-assistant 중계 뒤의 「내 브라우저」 에 붙는 작은 CDP 클라이언트다.
// 중계는 Chrome 디버깅 엔드포인트처럼 부른다. HTTP `json/*` 와 페이지 WebSocket 이다.
// naver-blog 플러그인과 같은 계약이다. 이 코드는 옛 저장소에서 따로 썼던 것을 옮겼다.

/** Bun 의 fetch 와 WebSocket 이 읽는 프록시 변수다. 중계 주소는 내부 주소라 프록시가 닿지 못한다. */
const PROXY_ENVIRONMENT_KEYS = ["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"];

const HTTP_TIMEOUT_MS = 5_000;
const COMMAND_TIMEOUT_MS = 30_000;
/** 사람이 브라우저를 쥐고 있으면 중계는 HTTP 423 으로 답한다. */
const HUMAN_HELD_STATUS = 423;
/** 사람이 브라우저를 가져가면 중계는 이 코드로 WebSocket 을 닫는다. */
export const HUMAN_HELD_CLOSE = 4423;

/**
 * naver-blog 플러그인처럼 프록시 변수를 지운 채 `start` 를 실행하고 되돌려 놓는다.
 * Bun 1.3.14 에서 측정해 보니 프로세스 시작 때 정한 프록시는 그대로 쓰여서, 실행 중에 변수가 바뀔 때만 효과가 있다.
 * fos-assistant 의 MCP 환경은 프록시를 정하지 않는다. 되돌려 놓으므로 어느 쪽이든
 * 다른 요청과 GitHub 요청에는 영향이 없다.
 */
function withoutProxy<T>(start: () => T): T {
  const saved = PROXY_ENVIRONMENT_KEYS.map((key) => [key, process.env[key]] as const);
  for (const key of PROXY_ENVIRONMENT_KEYS) delete process.env[key];
  try {
    return start();
  } finally {
    for (const [key, value] of saved) if (value !== undefined) process.env[key] = value;
  }
}

const unreachable = () => new CareerError("CAREER_BROWSER_UNREACHABLE");
const humanHeld = (tab?: string) => new CareerError("CAREER_BROWSER_IN_USE", tab ? { tab } : undefined);

/** 설치가 `CAREER_BROWSER_URL` 에 넣은 중계 주소다. 비었거나 없으면 undefined 다. */
export function browserEndpoint(env: Record<string, string | undefined>): string | undefined {
  const value = configuredValue(env.CAREER_BROWSER_URL);
  return value ? value.replace(/\/+$/, "") : undefined;
}

/** 디버깅 HTTP 엔드포인트를 부른다. JSON 이 아닌 응답은 글자로 돌려준다. 실패 응답의 본문은 남기지 않는다. */
export async function httpJson(
  endpoint: string,
  path: string,
  { method = "GET", timeoutMs = HTTP_TIMEOUT_MS }: { method?: string; timeoutMs?: number } = {},
): Promise<unknown> {
  let text: string;
  try {
    const response = await withoutProxy(() =>
      fetch(`${endpoint}${path}`, {
        method,
        signal: AbortSignal.timeout(Math.max(1, timeoutMs)),
        redirect: "error",
      }),
    );
    if (response.status === HUMAN_HELD_STATUS) throw humanHeld();
    if (!response.ok) throw unreachable();
    text = await response.text();
  } catch (error) {
    if (error instanceof CareerError) throw error;
    throw unreachable();
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/**
 * 브라우저가 준 WebSocket 주소에서 경로만 떼어 우리가 받은 호스트에 붙인다.
 * 경로는 받은 엔드포인트 경로(중계의 접근 토큰)와 `devtools/` 아래여야 한다.
 */
export function wsUrlFor(endpoint: string, webSocketDebuggerUrl: string): string {
  let base: URL;
  let debuggerPath: string;
  try {
    base = new URL(endpoint);
    debuggerPath = new URL(webSocketDebuggerUrl).pathname;
  } catch {
    throw unreachable();
  }
  const prefix = base.pathname.replace(/\/+$/, "");
  if (!debuggerPath.startsWith(`${prefix}/devtools/`)) throw unreachable();
  return `${base.protocol === "https:" ? "wss:" : "ws:"}//${base.host}${debuggerPath}`;
}

function pageOf(wsUrl: string): string | undefined {
  return /\/devtools\/page\/([A-Za-z0-9]+)$/.exec(wsUrl)?.[1];
}

type Pending = { resolve: (value: any) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> };

export type Evaluation<T> = { ok: true; value: T } | { ok: false };

/** CDP WebSocket 하나다. 명령은 id 로 짝짓고 명령마다 시간 한도가 있다. */
export class CdpSession {
  private nextId = 1;
  private closed = false;
  private failure: () => CareerError = unreachable;
  private readonly pending = new Map<number, Pending>();
  private readonly waiters = new Map<string, Set<() => void>>();

  private constructor(
    private readonly socket: WebSocket,
    readonly tab: string | undefined,
  ) {
    socket.addEventListener("message", (event) => this.receive(event.data));
    socket.addEventListener("close", (event) => {
      if (event.code === HUMAN_HELD_CLOSE) this.failure = () => humanHeld(this.tab);
      this.shutdown();
    });
    socket.addEventListener("error", () => this.shutdown());
  }

  /** `Origin` 헤더 없이 연결한다. 중계는 웹 페이지에서 온 것 같은 요청을 거절한다. */
  static connect(wsUrl: string, timeoutMs = COMMAND_TIMEOUT_MS): Promise<CdpSession> {
    return new Promise((resolve, reject) => {
      let socket: WebSocket;
      try {
        socket = withoutProxy(() => new WebSocket(wsUrl));
      } catch {
        reject(unreachable());
        return;
      }
      const timer = setTimeout(() => {
        socket.close();
        reject(unreachable());
      }, Math.max(1, timeoutMs));
      const fail = (event: Event) => {
        clearTimeout(timer);
        reject((event as CloseEvent).code === HUMAN_HELD_CLOSE ? humanHeld(pageOf(wsUrl)) : unreachable());
      };
      socket.addEventListener("error", fail, { once: true });
      socket.addEventListener("close", fail, { once: true });
      socket.addEventListener(
        "open",
        () => {
          clearTimeout(timer);
          socket.removeEventListener("error", fail);
          socket.removeEventListener("close", fail);
          resolve(new CdpSession(socket, pageOf(wsUrl)));
        },
        { once: true },
      );
    });
  }

  /** 명령을 보내고 같은 id 의 응답을 기다린다. 브라우저의 오류 문구는 버린다. */
  send<T = any>(method: string, params: Record<string, unknown> = {}, timeoutMs = COMMAND_TIMEOUT_MS): Promise<T> {
    if (this.closed) return Promise.reject(this.failure());
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

  /** 페이지에서 표현식을 실행한다. 페이지 예외는 `{ok: false}` 이고, 연결이 끊기면 예외를 던진다. */
  async evaluate<T>(expression: string, timeoutMs = COMMAND_TIMEOUT_MS): Promise<Evaluation<T>> {
    const reply = await this.send<{ result?: { value?: unknown }; exceptionDetails?: unknown }>(
      "Runtime.evaluate",
      { expression, awaitPromise: true, returnByValue: true },
      timeoutMs,
    );
    if (!reply || reply.exceptionDetails) return { ok: false };
    return { ok: true, value: reply.result?.value as T };
  }

  /** 탭을 이동시키고 load 이벤트를 기다린다. */
  async navigate(url: string, timeoutMs = COMMAND_TIMEOUT_MS): Promise<void> {
    await this.send("Page.enable", {}, timeoutMs);
    const loaded = this.waitFor("Page.loadEventFired", timeoutMs);
    await this.send("Page.navigate", { url }, timeoutMs);
    await loaded;
  }

  close(): void {
    try {
      this.socket.close();
    } catch {
      // 이미 닫혔다
    }
    this.shutdown();
  }

  private waitFor(event: string, timeoutMs: number): Promise<void> {
    if (this.closed) return Promise.reject(this.failure());
    return new Promise((resolve, reject) => {
      const set = this.waiters.get(event) ?? new Set();
      this.waiters.set(event, set);
      const done = () => {
        clearTimeout(timer);
        set.delete(done);
        if (this.closed) reject(this.failure());
        else resolve();
      };
      const timer = setTimeout(() => {
        set.delete(done);
        reject(unreachable());
      }, Math.max(1, timeoutMs));
      set.add(done);
    });
  }

  private receive(data: unknown) {
    let message: { id?: number; method?: string; result?: unknown; error?: unknown };
    try {
      message = JSON.parse(String(data));
    } catch {
      return;
    }
    if (typeof message.id === "number") {
      const entry = this.pending.get(message.id);
      if (!entry) return;
      this.pending.delete(message.id);
      clearTimeout(entry.timer);
      if (message.error) entry.reject(unreachable());
      else entry.resolve(message.result);
      return;
    }
    if (message.method) for (const done of [...(this.waiters.get(message.method) ?? [])]) done();
  }

  private shutdown() {
    if (this.closed) return;
    this.closed = true;
    for (const [id, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(this.failure());
      this.pending.delete(id);
    }
    for (const set of this.waiters.values()) for (const done of [...set]) done();
  }
}

/** 도구가 탭을 열고 닫는 방법이다. 테스트는 대역을 넘긴다. */
export interface BrowserLink {
  openTab(url: string): Promise<{ tabId: string; session: CdpSession }>;
  closeTab(tabId: string): Promise<void>;
}

type TargetInfo = { id?: unknown; webSocketDebuggerUrl?: unknown };

export function relayBrowser(endpoint: string): BrowserLink {
  return {
    async openTab(url) {
      const target = (await httpJson(endpoint, `/json/new?${encodeURI(url)}`, { method: "PUT" })) as TargetInfo;
      if (typeof target?.id !== "string" || typeof target.webSocketDebuggerUrl !== "string") throw unreachable();
      const session = await CdpSession.connect(wsUrlFor(endpoint, target.webSocketDebuggerUrl));
      return { tabId: target.id, session };
    },
    async closeTab(tabId) {
      // 열린 화면 넘기기 카드가 가리키는 탭은 409 로 거절된다. 예상한 일이라 무시한다.
      try {
        await httpJson(endpoint, `/json/close/${encodeURIComponent(tabId)}`);
      } catch {
        // ignore
      }
    },
  };
}
