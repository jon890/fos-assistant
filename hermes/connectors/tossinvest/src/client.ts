import {
  bounded,
  bodyCode,
  isTimeout,
  tokenErrorCode,
  apiErrorCode,
  type Exchange,
} from "./transport.ts";
import {
  validAccountSeq,
  API_BASE,
  REQUEST_TIMEOUT_MS,
  TOKEN_MARGIN_MS,
  TOKEN_MIN_TTL_MS,
} from "./constants.ts";
import { TossinvestError } from "./errors.ts";
import { clearProxyEnvironment, type Env } from "./runtime.ts";
import {
  operationFor,
  parseApiResponse,
  type Operation,
} from "./api-contract.ts";
import {
  queueRequest,
  observeRateLimit,
  clearRateLimits,
  withDeadline,
} from "./rate-limit.ts";

export interface TossinvestOptions {
  apiBase?: string;
  timeoutMs?: number;
  env?: Env;
}
export interface RequestOptions {
  query?: URLSearchParams;
  /** 참이면 `X-Tossinvest-Account` 헤더에 `TOSSINVEST_ACCOUNT_SEQ` 를 싣는다. */
  account?: boolean;
}
/** 토큰 발급 응답을 이 시각(ms)까지만 쓴다. 토큰은 프로세스 메모리에만 둔다. */
interface CachedToken {
  value: string;
  expiresAt: number;
}

const RETRY_CODES = new Set(["token-revoked", "expired-token"]);

type Identity = {
  origin: string;
  clientId: string;
  secret: string;
  users: number;
  clearing: boolean;
  cached: CachedToken | null;
  issuing: Promise<string> | null;
};
// 객체 자체가 불투명 identity다. 비밀값이나 해시를 키, 파일, 로그에 남기지 않는다.
const identities = new Set<Identity>();
function sweep() {
  for (const state of identities) {
    if (
      state.clearing ||
      (state.cached && state.cached.expiresAt <= Date.now())
    )
      state.cached = null;
    if (!state.users && !state.issuing && !state.cached)
      identities.delete(state);
  }
}
export function clearClientState(origin?: string) {
  for (const state of identities) {
    // 병렬 요청 하나가 먼저 실패해도 나머지 요청의 마지막 참조가 끝나면 정리한다.
    if (!origin || state.origin === origin) state.clearing = true;
  }
  sweep();
  clearRateLimits(origin);
}
export function clientStateSize() {
  sweep();
  return identities.size;
}
process.once("exit", () => clearClientState());

export class Tossinvest {
  readonly env: Env;
  readonly apiBase: string;
  readonly timeoutMs: number;

  constructor(options: TossinvestOptions) {
    clearProxyEnvironment();
    this.env = options.env ?? process.env;
    const url = new URL(options.apiBase ?? API_BASE);
    if (
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      url.pathname !== "/" ||
      (url.origin !== API_BASE &&
        !(
          url.protocol === "http:" &&
          ["127.0.0.1", "[::1]"].includes(url.hostname)
        ))
    )
      throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
    this.apiBase = url.origin;
    this.timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS;
  }

  private acquire(): Identity {
    sweep();
    const clientId = this.env.TOSSINVEST_CLIENT_ID?.trim();
    const secret = this.env.TOSSINVEST_CLIENT_SECRET?.trim();
    if (!clientId || !secret)
      throw new TossinvestError("TOSSINVEST_UNAUTHORIZED");
    let state = [...identities].find(
      (item) =>
        !item.clearing &&
        item.origin === this.apiBase &&
        item.clientId === clientId &&
        item.secret === secret,
    );
    if (!state) {
      state = {
        origin: this.apiBase,
        clientId,
        secret,
        users: 0,
        clearing: false,
        cached: null,
        issuing: null,
      };
      identities.add(state);
    }
    state.users++;
    return state;
  }
  private release(state: Identity) {
    state.users--;
    sweep();
  }
  private budget(state: Identity) {
    return `${state.origin}\n${state.clientId}`;
  }

  private async exchange(
    operation: Operation,
    path: string,
    init: RequestInit,
    deadline: number,
    state: Identity,
  ): Promise<Exchange> {
    return queueRequest(
      this.budget(state),
      operation.group,
      deadline,
      async () => {
        clearProxyEnvironment();
        let response: Response;
        try {
          response = await fetch(`${this.apiBase}${path}`, {
            ...init,
            redirect: "manual",
            signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())),
          });
        } catch {
          throw new TossinvestError("TOSSINVEST_NETWORK");
        }
        observeRateLimit(
          this.budget(state),
          operation.group,
          response.status,
          response.headers,
        );
        let raw: Uint8Array;
        try {
          raw = await bounded(response);
        } catch (error) {
          throw new TossinvestError(
            isTimeout(error) ? "TOSSINVEST_NETWORK" : "TOSSINVEST_UNAVAILABLE",
          );
        }
        let data: any;
        try {
          if (raw.length)
            data = parseApiResponse(
              new TextDecoder().decode(raw),
              operation,
              response.status,
            );
        } catch {
          data = undefined;
        }
        return {
          status: response.status,
          ok: response.ok,
          data,
          empty: !raw.length,
          retryAfterMs: 0,
        };
      },
    );
  }

  async token(): Promise<string> {
    const state = this.acquire();
    try {
      return await this.getToken(state, Date.now() + this.timeoutMs);
    } finally {
      this.release(state);
    }
  }
  private async getToken(state: Identity, deadline: number): Promise<string> {
    if (state.cached && state.cached.expiresAt > Date.now())
      return state.cached.value;
    state.cached = null;
    if (!state.issuing)
      state.issuing = this.issue(state, deadline).finally(() => {
        state.issuing = null;
        sweep();
      });
    return withDeadline(state.issuing, deadline);
  }
  private async issue(state: Identity, deadline: number): Promise<string> {
    const result = await this.exchange(
      operationFor("POST", "/oauth2/token"),
      "/oauth2/token",
      {
        method: "POST",
        headers: {
          "content-type": "application/x-www-form-urlencoded",
          accept: "application/json",
        },
        body: new URLSearchParams({
          grant_type: "client_credentials",
          client_id: state.clientId,
          client_secret: state.secret,
        }),
      },
      deadline,
      state,
    );
    if (!result.ok) throw new TossinvestError(tokenErrorCode(result));
    const value = result.data?.access_token;
    const seconds = result.data?.expires_in;
    if (
      typeof value !== "string" ||
      !value ||
      result.data?.token_type !== "Bearer" ||
      !(
        (typeof seconds === "number" && Number.isSafeInteger(seconds)) ||
        typeof seconds === "string"
      )
    )
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    let lifetime: bigint;
    try {
      lifetime = BigInt(seconds) * 1000n;
    } catch {
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    }
    const now = Date.now();
    if (lifetime <= 0n || lifetime > BigInt(Number.MAX_SAFE_INTEGER - now))
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    const ms = Number(lifetime);
    const ttl =
      ms > TOKEN_MARGIN_MS
        ? ms - TOKEN_MARGIN_MS
        : Math.min(ms, Math.max(ms / 2, TOKEN_MIN_TTL_MS));
    state.cached = { value, expiresAt: now + ttl };
    return value;
  }
  private accountSeq(): string {
    const seq = this.env.TOSSINVEST_ACCOUNT_SEQ ?? "";
    if (!validAccountSeq(seq))
      throw new TossinvestError("TOSSINVEST_ACCOUNT_NOT_FOUND");
    return seq;
  }

  /** GET의 429와 토큰 재발급을 각각 한 번만 허용해 전송은 최대 세 번이다. */
  async request(path: string, options: RequestOptions = {}): Promise<any> {
    const operation = operationFor("GET", path);
    const account = options.account ? this.accountSeq() : null;
    const state = this.acquire();
    const deadline = Date.now() + this.timeoutMs;
    try {
      let rateRetried = false;
      let tokenRetried = false;
      let token = await this.getToken(state, deadline);
      for (let sent = 0; sent < 3; sent++) {
        const query = options.query?.toString();
        const headers: Record<string, string> = {
          authorization: `Bearer ${token}`,
          accept: "application/json",
        };
        if (account !== null) headers["x-tossinvest-account"] = account;
        const response = await this.exchange(
          operation,
          `${path}${query ? `?${query}` : ""}`,
          { method: "GET", headers },
          deadline,
          state,
        );
        if (response.status === 429 && !rateRetried) {
          rateRetried = true;
          continue;
        }
        if (
          response.status === 401 &&
          RETRY_CODES.has(bodyCode(response.data)) &&
          !tokenRetried
        ) {
          tokenRetried = true;
          if (state.cached?.value === token) state.cached = null;
          token = await this.getToken(state, deadline);
          continue;
        }
        if (
          response.status === 401 &&
          tokenRetried &&
          bodyCode(response.data) === "token-revoked"
        )
          throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
        if (!response.ok) throw new TossinvestError(apiErrorCode(response));
        if (
          !response.data ||
          typeof response.data !== "object" ||
          Array.isArray(response.data)
        )
          throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
        return response.data;
      }
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    } finally {
      this.release(state);
    }
  }
}
