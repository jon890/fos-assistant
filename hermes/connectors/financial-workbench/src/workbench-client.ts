import {
  BASE_URL_ENV,
  REQUEST_MAX_BYTES,
  RESPONSE_MAX_BYTES,
  TEXT_MAX_CHARS,
  TIMEOUT_MS,
  TOKEN,
  TOKEN_ENV,
} from "./constants.ts";
import { WorkbenchError } from "./errors.ts";
import { clearProxyEnvironment, type Env } from "./runtime.ts";

export interface WorkbenchOptions {
  /** 테스트가 로컬 HTTP 대역을 가리킬 때만 쓴다. 운영은 env 의 https 주소만 받는다. */
  baseUrl?: string;
  timeoutMs?: number;
  env?: Env;
}

/**
 * 운영자가 준 기준 주소를 검사한다. https 이고 계정 정보, 쿼리, fragment 가 없어야 한다.
 * 토큰을 실어 보내는 곳이므로 틀린 값은 고쳐 쓰지 않고 거절한다.
 */
export function baseUrlOf(value: string | undefined): string {
  const raw = value?.trim() ?? "";
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new WorkbenchError("WORKBENCH_NOT_CONFIGURED");
  }
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    raw.includes("?") ||
    raw.includes("#")
  )
    throw new WorkbenchError("WORKBENCH_NOT_CONFIGURED");
  return `${url.origin}${url.pathname.replace(/\/+$/, "")}`;
}

async function bounded(response: Response): Promise<Uint8Array> {
  const length = Number(response.headers.get("content-length") ?? 0);
  if (Number.isFinite(length) && length > RESPONSE_MAX_BYTES)
    throw new WorkbenchError("WORKBENCH_UNAVAILABLE");
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
        throw new WorkbenchError("WORKBENCH_UNAVAILABLE");
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

/** Retry-After 초나 본문의 `details.retryAfterSeconds` 를 정수로 읽는다. 둘 다 없으면 칸을 내지 않는다. */
function retryAfter(response: Response, data: any): Record<string, number> {
  const header = response.headers.get("retry-after")?.trim() ?? "";
  const candidates = [
    /^\d{1,6}$/.test(header) ? Number(header) : undefined,
    data?.details?.retryAfterSeconds,
  ];
  const seconds = candidates.find(
    (value) => Number.isInteger(value) && value >= 0 && value <= 86_400,
  );
  return seconds === undefined ? {} : { retry_after_seconds: seconds };
}

/** 긴 글을 잘라 끝에 `…` 를 붙인다. 잘린 칸이 있으면 `cut.value` 를 참으로 둔다. 워크벤치의 `...Truncated` 칸은 그대로 둔다. */
function clip(value: unknown, cut: { value: boolean }): unknown {
  if (typeof value === "string") {
    const chars = Array.from(value);
    if (chars.length <= TEXT_MAX_CHARS) return value;
    cut.value = true;
    return `${chars.slice(0, TEXT_MAX_CHARS).join("")}…`;
  }
  if (Array.isArray(value)) return value.map((item) => clip(item, cut));
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, clip(item, cut)]),
    );
  return value;
}

/** 필드 경로 모양이다. 예: `trades[2].quantity`, `proposals[0].payload.type`. 사유 글은 싣지 않는다. */
const FIELD_PATH =
  /^[A-Za-z][A-Za-z0-9]{0,39}(?:\[\d{1,3}\])?(?:\.[A-Za-z][A-Za-z0-9]{0,39}(?:\[\d{1,3}\])?){0,5}$/;
const RULE = /^[a-z][a-z0-9-]{0,63}$/;
const FIELD_MAX = 20;

/** 몇 번째 항목이 틀렸는지 `details.index` 나 첫 필드 경로의 번호로 읽는다. */
function itemIndex(details: any, fields: string[]): Record<string, number> {
  const candidates = [
    details?.index,
    ...fields
      .map((field) => /^[A-Za-z]+\[(\d{1,3})\]/.exec(field)?.[1])
      .map(Number),
  ];
  const index = candidates.find(
    (value) => Number.isInteger(value) && value >= 0 && value < 1000,
  );
  return index === undefined ? {} : { item_index: index };
}

/**
 * 실패한 응답을 오류 코드로 바꾼다. 서비스의 오류 글은 싣지 않는다.
 * 쓰기의 400, 404, 409 는 몇 번째 항목인지와 필드 경로, 규칙 이름만 옮긴다.
 */
function failure(response: Response, data: any, notFound: string) {
  const code = typeof data?.code === "string" ? data.code : "";
  const details = data?.details;
  const fields = Object.keys(
    details?.fieldErrors && typeof details.fieldErrors === "object"
      ? details.fieldErrors
      : {},
  )
    .filter((field) => FIELD_PATH.test(field))
    .slice(0, FIELD_MAX);
  const located = itemIndex(details, fields);
  switch (response.status) {
    case 401:
      return new WorkbenchError("WORKBENCH_UNAUTHORIZED");
    case 403:
      return new WorkbenchError(
        code === "INSUFFICIENT_SCOPE"
          ? "WORKBENCH_INSUFFICIENT_SCOPE"
          : "WORKBENCH_FORBIDDEN",
      );
    case 400: {
      const rule =
        typeof details?.rule === "string" && RULE.test(details.rule)
          ? { rule: details.rule }
          : {};
      return new WorkbenchError("WORKBENCH_INVALID_INPUT", {
        ...located,
        ...(fields.length ? { fields } : {}),
        ...rule,
      });
    }
    case 404:
      // 쓰기에서 가리킨 노드나 체결이 없으면 몇 번째 항목인지 함께 온다.
      if ("item_index" in located)
        return new WorkbenchError("WORKBENCH_NOT_FOUND", located);
      return new WorkbenchError(notFound);
    case 409:
      if (code === "PROPOSAL_LIMIT")
        return new WorkbenchError("WORKBENCH_PROPOSAL_LIMIT", {
          ...count("max_pending", details?.max),
          ...count("pending", details?.pending),
        });
      if (code === "TRANSIENT_CONFLICT")
        return new WorkbenchError("WORKBENCH_UNAVAILABLE");
      return new WorkbenchError("WORKBENCH_CONFLICT", located);
    case 413:
      return new WorkbenchError("WORKBENCH_PAYLOAD_TOO_LARGE");
    case 429:
      return new WorkbenchError(
        "WORKBENCH_RATE_LIMITED",
        retryAfter(response, data),
      );
    default:
      return new WorkbenchError("WORKBENCH_UNAVAILABLE");
  }
}

function count(name: string, value: unknown): Record<string, number> {
  return Number.isInteger(value) &&
    (value as number) >= 0 &&
    (value as number) <= 1_000_000
    ? { [name]: value as number }
    : {};
}

export class Workbench {
  readonly env: Env;
  readonly baseUrl?: string;
  readonly timeoutMs: number;
  constructor(options: WorkbenchOptions = {}) {
    clearProxyEnvironment();
    this.env = options.env ?? process.env;
    this.baseUrl = options.baseUrl;
    this.timeoutMs = options.timeoutMs ?? TIMEOUT_MS;
  }

  token() {
    const token = this.env[TOKEN_ENV]?.trim() ?? "";
    if (!TOKEN.test(token)) throw new WorkbenchError("WORKBENCH_UNAUTHORIZED");
    return token;
  }

  /**
   * `/api/v1` 아래 경로 하나를 GET 으로 부른다. 429 에도 다시 부르지 않는다.
   * id 가 없는 경로의 404 는 항목이 아니라 기준 주소가 틀린 것이라 `notFound` 를 따로 받는다.
   */
  get(
    path: string,
    query?: URLSearchParams,
    notFound = "WORKBENCH_NOT_CONFIGURED",
  ): Promise<any> {
    return this.call("GET", path, { query, notFound });
  }

  /**
   * 쓰기 경로에 JSON 본문을 POST 한다. 다시 부르지 않는다.
   * 보낸 뒤 답을 읽지 못하면 저장됐는지 모르므로 `WORKBENCH_WRITE_UNKNOWN` 으로 끝낸다.
   */
  post(path: string, body: unknown): Promise<any> {
    const text = JSON.stringify(body);
    if (new TextEncoder().encode(text).byteLength > REQUEST_MAX_BYTES)
      throw new WorkbenchError("WORKBENCH_PAYLOAD_TOO_LARGE");
    return this.call("POST", path, { body: text });
  }

  private async call(
    method: "GET" | "POST",
    path: string,
    options: { query?: URLSearchParams; body?: string; notFound?: string },
  ): Promise<any> {
    const base = this.baseUrl ?? baseUrlOf(this.env[BASE_URL_ENV]);
    const token = this.token();
    const { query, body } = options;
    const search = query && [...query].length ? `?${query}` : "";
    // GET 은 다시 불러도 바뀌는 것이 없다. POST 는 보낸 뒤의 실패를 결과 모름으로 낸다.
    const lost =
      method === "POST" ? "WORKBENCH_WRITE_UNKNOWN" : "WORKBENCH_UNAVAILABLE";
    let response: Response;
    clearProxyEnvironment();
    try {
      response = await fetch(`${base}/api/v1${path}${search}`, {
        method,
        headers: {
          authorization: `Bearer ${token}`,
          accept: "application/json",
          ...(body === undefined ? {} : { "content-type": "application/json" }),
        },
        body,
        // 다른 곳으로 넘겨 토큰이 따라가지 않게 한다.
        redirect: "manual",
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch {
      throw new WorkbenchError(lost);
    }
    let raw: Uint8Array;
    try {
      raw = await bounded(response);
    } catch {
      throw new WorkbenchError(response.ok ? lost : "WORKBENCH_UNAVAILABLE");
    }
    let data: any = undefined;
    try {
      if (raw.length) data = JSON.parse(new TextDecoder().decode(raw));
    } catch {
      data = undefined;
    }
    if (!response.ok)
      throw failure(
        response,
        data,
        options.notFound ?? "WORKBENCH_NOT_CONFIGURED",
      );
    if (!data || typeof data !== "object" || Array.isArray(data))
      throw new WorkbenchError(lost);
    const cut = { value: false };
    const clipped = clip(data, cut) as Record<string, unknown>;
    return cut.value ? { ...clipped, textTruncated: true } : clipped;
  }
}
