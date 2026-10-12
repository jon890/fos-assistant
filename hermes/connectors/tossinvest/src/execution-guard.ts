import { createHash } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { bounded } from "./transport.ts";
import { clock } from "./rate-limit.ts";
import type { Env } from "./runtime.ts";
import { validAccountSeq } from "./constants.ts";
import { reject, type Args } from "./trading-body.ts";

export const sha256 = (raw: string) =>
  createHash("sha256").update(raw, "utf8").digest("hex");

/** 중복 키와 깊이·바이트 상한을 tree 변환 전에 검사한다. 값을 보정하지 않는다. */
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

/** SDK의 schema 오류로 callback이 생략돼도 env에 권한을 남기지 않는다. */
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
type Authority = ReturnType<typeof takeApproval>;
const canonicalUuid = (value: unknown) =>
  typeof value === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const now = () => Math.max(Date.now(), clock.now());

/** claim은 인증·DB 소비의 producer다. 이 소비자는 원문·scope·응답·만료를 대조한다. */
export async function claimApproval(
  authority: Authority,
  env: Env,
  tool: string,
  input: Args,
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
  const parsed = strictJson(raw);
  if (!isDeepStrictEqual(parsed, input)) reject();
  const account = env.TOSSINVEST_ACCOUNT_SEQ;
  if (!account || !validAccountSeq(account) || input.account_seq !== account)
    reject();
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
  const payload = strictJson(
    new TextDecoder("utf-8", { fatal: true }).decode(payloadBytes),
    3072,
    true,
  );
  const digest = sha256(raw);
  const scope = { account_seq: account };
  const expires =
    typeof payload.expiresAt === "string" ? Date.parse(payload.expiresAt) : NaN;
  const issued =
    typeof payload.issuedAt === "string" ? Date.parse(payload.issuedAt) : NaN;
  if (
    payload.v !== 1 ||
    payload.tool !== tool ||
    payload.argsSha256 !== digest ||
    payload.scopeSha256 !== sha256(JSON.stringify(scope)) ||
    !canonicalUuid(payload.ticketId) ||
    !Number.isFinite(expires) ||
    !Number.isFinite(issued) ||
    expires <= issued ||
    expires - issued > 60000 ||
    issued > now()
  )
    reject();
  const beforeSend = () => {
    if (now() >= expires || env.TOSSINVEST_ACCOUNT_SEQ !== account) reject();
  };
  beforeSend();
  const endpoint = new URL(url);
  if (
    !["http:", "https:"].includes(endpoint.protocol) ||
    endpoint.username ||
    endpoint.password ||
    endpoint.search ||
    endpoint.hash ||
    endpoint.pathname !== "/internal/connector-executions/claim"
  )
    reject();
  const response = await fetch(endpoint, {
    method: "POST",
    redirect: "manual",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ ticket, tool, argsSha256: digest, scope }),
    signal: AbortSignal.timeout(
      Math.max(1, Math.ceil(Math.min(10000, expires - now()))),
    ),
  });
  if (response.status !== 200) reject();
  const bytes = await bounded(response);
  if (bytes.length > 4096) reject();
  const result = strictJson(
    new TextDecoder("utf-8", { fatal: true }).decode(bytes),
    4096,
    true,
  );
  if (
    !isDeepStrictEqual(Object.keys(result).sort(), [
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
  beforeSend();
  return beforeSend;
}
