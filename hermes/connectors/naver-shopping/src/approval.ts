/**
 * 승인한 실행의 일회 권한을 대조하고 claim 으로 한 번 소비한다. 토스 플러그인의 `execution-guard.ts` 와 같은 검사이고 코드는
 * 나누지 않는다(design.md 「채우기(REQ11, PR3c)」). 주문서 준비는 계좌 같은 공개 칸이 없어 scope 는 빈 object 다.
 * 어긋나면 이유를 싣지 않고 `NAVER_SHOPPING_CLAIM_REJECTED` 다. ticket 과 실행 원문은 결과와 로그에 싣지 않는다.
 */
import { createHash } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { ToolError } from "./errors.ts";
import type { Env } from "./session.ts";

const reject = (): never => {
  throw new ToolError("NAVER_SHOPPING_CLAIM_REJECTED");
};

const sha256 = (raw: string) =>
  createHash("sha256").update(raw, "utf8").digest("hex");

/** 빈 scope 의 글. Control Plane 이 저장한 scope 글과 같아야 해시가 맞는다. */
const SCOPE = {};
const CLAIM_PATH = "/internal/connector-executions/claim";
const RESPONSE_MAX = 4096;

/** 중복 키와 깊이·바이트 상한을 tree 로 바꾸기 전에 본다. 값을 보정하지 않는다. */
export function strictJson(
  raw: string,
  limit = 16384,
  integersOnly = false,
): any {
  if (!raw.isWellFormed() || Buffer.byteLength(raw) > limit) reject();
  let position = 0;
  const whitespace = () => {
    while (/[\x20\t\r\n]/.test(raw[position] ?? "!")) position++;
  };
  const string = (): string => {
    const start = position++;
    while (position < raw.length) {
      const char = raw[position++];
      if (char === "\\") position++;
      else if (char === '"') {
        const value = JSON.parse(raw.slice(start, position));
        if (!value.isWellFormed()) reject();
        return value;
      }
    }
    return reject();
  };
  const visit = (depth: number): any => {
    if (depth > 5) reject();
    whitespace();
    const char = raw[position];
    if (char === '"') return string();
    if (char === "{" || char === "[") {
      position++;
      const object = char === "{";
      const end = object ? "}" : "]";
      const keys = new Set<string>();
      const pairs: Array<[string, any]> = [];
      const values: any[] = [];
      whitespace();
      if (raw[position] !== end) {
        while (true) {
          whitespace();
          if (object) {
            if (raw[position] !== '"') reject();
            const key = string();
            if (keys.has(key)) reject();
            keys.add(key);
            whitespace();
            if (raw[position++] !== ":") reject();
            pairs.push([key, visit(depth + 1)]);
          } else values.push(visit(depth + 1));
          whitespace();
          if (raw[position] !== ",") break;
          position++;
        }
      }
      if (raw[position++] !== end) reject();
      return object ? Object.fromEntries(pairs) : values;
    }
    const token =
      /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(
        raw.slice(position),
      );
    if (!token) return reject();
    position += token[0].length;
    const value = JSON.parse(token[0]);
    if (
      integersOnly &&
      typeof value === "number" &&
      !/^[1-9][0-9]*$/.test(token[0])
    )
      reject();
    if (typeof value === "number" && !Number.isFinite(value)) reject();
    return value;
  };
  try {
    const parsed = visit(0);
    whitespace();
    if (position !== raw.length) reject();
    return parsed;
  } catch {
    return reject();
  }
}

/** 응답 본문을 상한까지만 읽는다. 넘으면 읽기를 멈추고 거절한다. */
async function bounded(response: Response) {
  const reader = response.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > RESPONSE_MAX) {
        await reader.cancel().catch(() => {});
        return reject();
      }
      chunks.push(value);
    }
  } catch {
    return reject();
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(
      Buffer.concat(chunks),
    );
  } catch {
    return reject();
  }
}

/** 서버를 만들 때 권한 env 를 꺼내고 지운다. 도구가 불리지 않아도 env 에 권한을 남기지 않는다. */
export function takeApproval(env: Env) {
  const authority = {
    raw: env.FOS_APPROVAL_ARGS_JSON,
    ticket: env.FOS_APPROVAL_TICKET,
    url: env.FOS_APPROVAL_CLAIM_URL,
    used: false,
  };
  for (const name of Object.keys(env))
    if (name.startsWith("FOS_APPROVAL_")) delete env[name];
  return authority;
}
export type Authority = ReturnType<typeof takeApproval>;

const canonicalUuid = (value: unknown) =>
  typeof value === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);

/**
 * 탭을 열기 전에 실행 원문, ticket, claim 주소를 대조한다(채우기 1단계). 통과하면 claim 을 한 번 보내는 함수를 돌려준다.
 * 권한은 한 번만 꺼낸다. 다시 부르면 거절이다.
 */
export function verifyApproval(
  authority: Authority,
  tool: string,
  input: unknown,
  now: () => number = Date.now,
) {
  if (authority.used) reject();
  authority.used = true;
  const { raw, ticket, url } = authority;
  authority.raw = authority.ticket = authority.url = undefined;
  if (
    typeof raw !== "string" ||
    typeof ticket !== "string" ||
    typeof url !== "string"
  )
    return reject();
  if (!isDeepStrictEqual(strictJson(raw), input)) reject();
  if (
    ticket.length > 4096 ||
    !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$/.test(ticket)
  )
    reject();
  const [encoded, signature] = ticket.split(".") as [string, string];
  const payloadBytes = Buffer.from(encoded, "base64url");
  if (
    payloadBytes.toString("base64url") !== encoded ||
    Buffer.from(signature, "base64url").toString("base64url") !== signature
  )
    reject();
  let payload: any;
  try {
    payload = strictJson(
      new TextDecoder("utf-8", { fatal: true }).decode(payloadBytes),
      3072,
      true,
    );
  } catch {
    return reject();
  }
  const digest = sha256(raw);
  const expires =
    typeof payload?.expiresAt === "string"
      ? Date.parse(payload.expiresAt)
      : NaN;
  const issued =
    typeof payload?.issuedAt === "string" ? Date.parse(payload.issuedAt) : NaN;
  if (
    payload?.v !== 1 ||
    payload.tool !== tool ||
    payload.argsSha256 !== digest ||
    payload.scopeSha256 !== sha256(JSON.stringify(SCOPE)) ||
    !canonicalUuid(payload.ticketId) ||
    !Number.isFinite(expires) ||
    !Number.isFinite(issued) ||
    expires <= issued ||
    expires - issued > 60000 ||
    issued > now() ||
    now() >= expires
  )
    reject();
  let endpoint: URL;
  try {
    endpoint = new URL(url);
  } catch {
    return reject();
  }
  if (
    !["http:", "https:"].includes(endpoint.protocol) ||
    endpoint.username ||
    endpoint.password ||
    endpoint.search ||
    endpoint.hash ||
    endpoint.pathname !== CLAIM_PATH
  )
    reject();
  /** claim 으로 ticket 을 한 번 소비한다. 200 과 정확한 응답이 아니면 거절이다. 채우기 직전 상한도 Control Plane 이 이때 본다. */
  return async function claim() {
    if (now() >= expires) reject();
    let response: Response;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        redirect: "manual",
        headers: {
          "content-type": "application/json",
          accept: "application/json",
        },
        body: JSON.stringify({
          ticket,
          tool,
          argsSha256: digest,
          scope: SCOPE,
        }),
        signal: AbortSignal.timeout(
          Math.max(1, Math.ceil(Math.min(10000, expires - now()))),
        ),
      });
    } catch {
      return reject();
    }
    if (response.status !== 200) {
      await response.body?.cancel().catch(() => {});
      return reject();
    }
    const text = await bounded(response);
    const result = strictJson(text, RESPONSE_MAX, true);
    if (
      !isDeepStrictEqual(Object.keys(result ?? {}).sort(), [
        "allowed",
        "expiresAt",
        "ticketId",
        "v",
      ]) ||
      result.v !== 1 ||
      result.allowed !== true ||
      result.ticketId !== payload.ticketId ||
      result.expiresAt !== payload.expiresAt
    )
      reject();
    // 응답을 기다리는 사이 만료됐으면 소비했어도 누르지 않는다.
    if (now() >= expires) reject();
  };
}
