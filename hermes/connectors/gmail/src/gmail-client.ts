import { TOKEN_URL, API_BASE, RESPONSE_MAX_BYTES } from "./constants.ts";
import { GmailError } from "./errors.ts";
import { clearProxyEnvironment, type Env } from "./runtime.ts";

export interface GmailOptions {
  tokenUrl?: string;
  apiBase?: string;
  timeoutMs?: number;
  env?: Env;
}
async function bounded(response: Response, error: string): Promise<Uint8Array> {
  const length = Number(response.headers.get("content-length") ?? 0);
  if (Number.isFinite(length) && length > RESPONSE_MAX_BYTES)
    throw new GmailError(error);
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.byteLength;
      if (total > RESPONSE_MAX_BYTES) throw new GmailError(error);
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
export class Gmail {
  readonly env: Env;
  readonly tokenUrl: string;
  readonly apiBase: string;
  readonly timeoutMs: number;
  constructor(options: GmailOptions) {
    clearProxyEnvironment();
    this.env = options.env ?? process.env;
    this.tokenUrl = options.tokenUrl ?? TOKEN_URL;
    this.apiBase = options.apiBase ?? API_BASE;
    this.timeoutMs = options.timeoutMs ?? 15_000;
  }
  async request(
    url: string,
    init: RequestInit,
    tokenEndpoint = false,
    unavailable = "GMAIL_UNAVAILABLE",
  ): Promise<any> {
    let response: Response;
    clearProxyEnvironment();
    try {
      response = await fetch(url, {
        ...init,
        redirect: "manual",
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch {
      throw new GmailError(unavailable);
    }
    let raw: Uint8Array;
    try {
      raw = await bounded(response, unavailable);
    } catch {
      throw new GmailError(unavailable);
    }
    let data: any = undefined;
    try {
      if (raw.length) data = JSON.parse(new TextDecoder().decode(raw));
    } catch {
      data = undefined;
    }
    if (!response.ok) {
      if (tokenEndpoint)
        throw new GmailError(
          data?.error === "invalid_grant" || data?.error === "invalid_client"
            ? "GMAIL_UNAUTHORIZED"
            : "GMAIL_UNAVAILABLE",
        );
      if (response.status === 401) throw new GmailError("GMAIL_UNAUTHORIZED");
      if (response.status === 403) throw new GmailError("GMAIL_FORBIDDEN");
      if (response.status === 400 || response.status === 404)
        throw new GmailError("GMAIL_INVALID_INPUT");
      throw new GmailError(
        response.status >= 500 ? unavailable : "GMAIL_UNAVAILABLE",
      );
    }
    if (raw.length === 0) return {};
    if (!data || typeof data !== "object" || Array.isArray(data))
      throw new GmailError(unavailable);
    return data;
  }
  async token() {
    const clientId = this.env.GMAIL_OAUTH_CLIENT_ID?.trim(),
      secret = this.env.GMAIL_OAUTH_CLIENT_SECRET?.trim(),
      refresh = this.env.GMAIL_OAUTH_REFRESH_TOKEN?.trim();
    if (!clientId || !secret || !refresh)
      throw new GmailError("GMAIL_UNAUTHORIZED");
    const body = new URLSearchParams({
      client_id: clientId,
      client_secret: secret,
      refresh_token: refresh,
      grant_type: "refresh_token",
    });
    const result = await this.request(
      this.tokenUrl,
      {
        method: "POST",
        headers: {
          "content-type": "application/x-www-form-urlencoded",
          accept: "application/json",
        },
        body,
      },
      true,
    );
    if (typeof result.access_token !== "string" || !result.access_token)
      throw new GmailError("GMAIL_UNAVAILABLE");
    return result.access_token;
  }
  async api(
    path: string,
    method = "GET",
    query?: URLSearchParams,
    body?: unknown,
    accessToken?: string,
  ) {
    const token = accessToken ?? (await this.token());
    const url = `${this.apiBase}${path}${query ? `?${query}` : ""}`;
    return this.request(
      url,
      {
        method,
        headers: {
          authorization: `Bearer ${token}`,
          accept: "application/json",
          ...(body === undefined ? {} : { "content-type": "application/json" }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      },
      false,
      method === "POST" && path === "/messages/send"
        ? "GMAIL_SEND_UNKNOWN"
        : "GMAIL_UNAVAILABLE",
    );
  }
  async labelList(accessToken?: string) {
    const result = await this.api(
      "/labels",
      "GET",
      undefined,
      undefined,
      accessToken,
    );
    return (result.labels ?? []).filter(
      (label: any) =>
        typeof label?.id === "string" && typeof label?.name === "string",
    );
  }
  async resolve(tokenLabels: any[], names: string[]) {
    const byName = new Map(tokenLabels.map((label) => [label.name, label.id]));
    const byId = new Map(tokenLabels.map((label) => [label.id, label.id]));
    const system = new Map<string, string>();
    for (const label of tokenLabels)
      if (String(label.type).toLowerCase() === "system") {
        system.set(String(label.id).toUpperCase(), label.id);
        system.set(String(label.name).toUpperCase(), label.id);
      }
    return names.map((name) => {
      const value =
        byName.get(name) ??
        byId.get(name) ??
        system.get(name.toUpperCase()) ??
        undefined;
      if (!value) throw new GmailError("GMAIL_INVALID_INPUT");
      return value;
    });
  }
}
