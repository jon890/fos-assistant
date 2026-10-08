import {
  ACCOUNT_SEQ,
  API_BASE,
  REQUEST_TIMEOUT_MS,
  RESPONSE_MAX_BYTES,
  TOKEN_MARGIN_MS,
} from "./constants.ts";
import { TossinvestError } from "./errors.ts";
import { clearProxyEnvironment, type Env } from "./runtime.ts";

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
/** HTTP 상태와 JSON 본문이다. 본문이 JSON 이 아니면 `data` 가 undefined 다. */
interface Exchange {
  status: number;
  ok: boolean;
  data: any;
  empty: boolean;
}
/** 토큰 발급 응답을 이 시각(ms)까지만 쓴다. 토큰은 프로세스 메모리에만 둔다. */
interface CachedToken {
  value: string;
  expiresAt: number;
}

async function bounded(response: Response): Promise<Uint8Array> {
  const length = Number(response.headers.get("content-length") ?? 0);
  if (Number.isFinite(length) && length > RESPONSE_MAX_BYTES)
    throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.byteLength;
      if (total > RESPONSE_MAX_BYTES)
        throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
      parts.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.byteLength;
  }
  return result;
}

const bodyCode = (data: any) =>
  typeof data?.error?.code === "string" ? data.error.code : "";
const RETRY_CODES = new Set(["token-revoked", "expired-token"]);

/** 토큰 발급 실패를 공통 어휘로 옮긴다. 본문 `error` 를 상태보다 먼저 본다. */
function tokenErrorCode(exchange: Exchange): string {
  const { status, data } = exchange;
  if (status === 403 && data?.error === "access_denied")
    return "TOSSINVEST_IP_NOT_ALLOWED";
  if (status === 400 || status === 401 || status === 403)
    return "TOSSINVEST_UNAUTHORIZED";
  if (status === 429) return "TOSSINVEST_RATE_LIMITED";
  return "TOSSINVEST_UNAVAILABLE";
}

/** API 실패를 공통 어휘로 옮긴다. 다시 받을 401 은 호출하는 쪽이 먼저 가려낸다. */
function apiErrorCode(exchange: Exchange): string {
  const { status } = exchange;
  const code = bodyCode(exchange.data);
  if (code === "ip-not-allowed") return "TOSSINVEST_IP_NOT_ALLOWED";
  if (code === "account-not-found") return "TOSSINVEST_ACCOUNT_NOT_FOUND";
  if (status === 401) return "TOSSINVEST_UNAUTHORIZED";
  if (status === 403) return "TOSSINVEST_FORBIDDEN";
  if (status === 400) return "TOSSINVEST_INVALID_INPUT";
  if (status === 404 && code === "stock-not-found")
    return "TOSSINVEST_INVALID_INPUT";
  if (status === 429) return "TOSSINVEST_RATE_LIMITED";
  return "TOSSINVEST_UNAVAILABLE";
}

export class Tossinvest {
  readonly env: Env;
  readonly apiBase: string;
  readonly timeoutMs: number;
  private cached: CachedToken | null = null;
  /** 진행 중인 발급 하나다. 그동안 오는 호출은 이것을 기다려 서로의 토큰을 무효로 만들지 않는다. */
  private issuing: Promise<string> | null = null;

  constructor(options: TossinvestOptions) {
    clearProxyEnvironment();
    this.env = options.env ?? process.env;
    this.apiBase = options.apiBase ?? API_BASE;
    this.timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS;
  }

  /** 연결 실패와 시간 초과는 NETWORK, 너무 큰 응답은 UNAVAILABLE 이다. 상태는 돌려주고 판정하지 않는다. */
  private async exchange(url: string, init: RequestInit): Promise<Exchange> {
    clearProxyEnvironment();
    let response: Response;
    try {
      response = await fetch(url, {
        ...init,
        redirect: "manual",
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch {
      throw new TossinvestError("TOSSINVEST_NETWORK");
    }
    let raw: Uint8Array;
    try {
      raw = await bounded(response);
    } catch {
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    }
    let data: any = undefined;
    try {
      if (raw.length) data = JSON.parse(new TextDecoder().decode(raw));
    } catch {
      data = undefined;
    }
    return {
      status: response.status,
      ok: response.ok,
      data,
      empty: raw.length === 0,
    };
  }

  /** 유효한 토큰이 있으면 그것을, 없으면 발급 하나를 기다려 돌려준다. */
  async token(): Promise<string> {
    if (this.cached && Date.now() < this.cached.expiresAt)
      return this.cached.value;
    if (!this.issuing)
      this.issuing = this.issue().finally(() => {
        this.issuing = null;
      });
    return this.issuing;
  }

  private async issue(): Promise<string> {
    const clientId = this.env.TOSSINVEST_CLIENT_ID?.trim();
    const secret = this.env.TOSSINVEST_CLIENT_SECRET?.trim();
    if (!clientId || !secret)
      throw new TossinvestError("TOSSINVEST_UNAUTHORIZED");
    const body = new URLSearchParams({
      grant_type: "client_credentials",
      client_id: clientId,
      client_secret: secret,
    });
    const result = await this.exchange(`${this.apiBase}/oauth2/token`, {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        accept: "application/json",
      },
      body,
    });
    if (!result.ok) throw new TossinvestError(tokenErrorCode(result));
    const value = result.data?.access_token;
    if (typeof value !== "string" || !value)
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    const seconds = Number(result.data?.expires_in);
    const ttl = Number.isFinite(seconds) ? seconds * 1000 - TOKEN_MARGIN_MS : 0;
    this.cached = ttl > 0 ? { value, expiresAt: Date.now() + ttl } : null;
    return value;
  }

  /** 다른 호출이 이미 새 토큰을 받았으면 그것을 버리지 않는다. */
  private discard(token: string) {
    if (this.cached?.value === token) this.cached = null;
  }

  /** 고른 계좌 순번이다. 비었거나 형식이 틀리면 요청하기 전에 거절한다. */
  private accountSeq(): string {
    const seq = this.env.TOSSINVEST_ACCOUNT_SEQ ?? "";
    if (!ACCOUNT_SEQ.test(seq))
      throw new TossinvestError("TOSSINVEST_ACCOUNT_NOT_FOUND");
    return seq;
  }

  private async send(
    path: string,
    options: RequestOptions,
    token: string,
    account: string | null,
  ): Promise<Exchange> {
    const query = options.query?.toString();
    const headers: Record<string, string> = {
      authorization: `Bearer ${token}`,
      accept: "application/json",
    };
    if (account !== null) headers["x-tossinvest-account"] = account;
    return this.exchange(`${this.apiBase}${path}${query ? `?${query}` : ""}`, {
      method: "GET",
      headers,
    });
  }

  /** API 를 한 번 부른다. 토큰이 철회됐거나 만료됐으면 다시 받아 한 번만 다시 보낸다. */
  async request(path: string, options: RequestOptions = {}): Promise<any> {
    const account = options.account ? this.accountSeq() : null;
    const first = await this.token();
    let result = await this.send(path, options, first, account);
    if (result.status === 401 && RETRY_CODES.has(bodyCode(result.data))) {
      this.discard(first);
      const second = await this.token();
      result = await this.send(path, options, second, account);
      // 두 번째도 철회됐으면 다른 프로세스와 토큰을 다툰 것이다. 자격 증명이 틀린 것이 아니다.
      if (result.status === 401 && bodyCode(result.data) === "token-revoked")
        throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    }
    if (!result.ok) throw new TossinvestError(apiErrorCode(result));
    if (result.empty) return {};
    const data = result.data;
    if (!data || typeof data !== "object" || Array.isArray(data))
      throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
    return data;
  }
}
