import { z } from "zod";
import { MARKETS, TICKER, UUID } from "./constants.ts";
import { invalid, pageSchema, paging } from "./inputs.ts";
import type { RegisterTool } from "./tool-registration.ts";
import type { Workbench } from "./workbench-client.ts";

const TRADES_MAX = 50;
const PROPOSALS_MAX = 20;
const TRADE_FIELDS = new Set([
  "broker",
  "externalId",
  "side",
  "market",
  "ticker",
  "name",
  "quantity",
  "price",
  "currency",
  "amount",
  "fee",
  "commission",
  "tax",
  "executedAt",
]);
const PROPOSAL_FIELDS = new Set([
  "kind",
  "payload",
  "rationale",
  "evidence",
  "dedupeKey",
]);
const EVIDENCE_LIMITS = { nodeIds: 20, tradeIds: 50, urls: 10 } as const;
const KINDS = new Set([
  "CREATE_NODE",
  "CREATE_RELATIONSHIP",
  "LINK_TRADE_DECISION",
  "STUDY_NOTE",
]);
const STATUSES = new Set(["PENDING", "ACCEPTED", "REJECTED"]);
// 오프셋이 붙은 ISO-8601 시각이다. 토스증권의 `filledAt` 은 `+09:00` 이 붙어 온다.
const OFFSET_TIME =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/;
const DECIMAL = /^(\d{1,20})(?:\.(\d{1,12}))?$/;
const CURRENCY = /^[A-Za-z]{3}$/;

type Item = Record<string, unknown>;

/** 앞뒤 공백을 지운 글이 1자 이상 `max` 자 이하인지 본다. 워크벤치와 같이 UTF-16 단위로 센다. */
function text(value: unknown, max: number, required = true) {
  if (value === undefined || value === null) {
    if (required) throw invalid();
    return undefined;
  }
  if (typeof value !== "string") throw invalid();
  const trimmed = value.trim();
  // 선택 칸의 빈 글은 워크벤치처럼 없는 값으로 본다.
  if (!trimmed && !required) return undefined;
  if (!trimmed || trimmed.length > max) throw invalid();
  return trimmed;
}

function oneOf(value: unknown, accepted: Set<string>) {
  if (typeof value !== "string") throw invalid();
  const upper = value.trim().toUpperCase();
  if (!accepted.has(upper)) throw invalid();
  return upper;
}

/** 토스증권의 decimal 글이나 수를 받아 소수 부분까지 그대로 나타낼 수 있을 때만 쓴다. */
function decimalText(value: unknown): string {
  const raw =
    typeof value === "number" && Number.isFinite(value)
      ? String(value)
      : typeof value === "string"
        ? value.trim()
        : "";
  const match = DECIMAL.exec(raw);
  if (!match) throw invalid();
  const whole = match[1]!.replace(/^0+(?=\d)/, "");
  const fraction = (match[2] ?? "").replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : whole;
}

/** JSON 수로 보내도 자리가 바뀌지 않는 값만 받는다. */
function amount(value: unknown, positive = false) {
  const exact = decimalText(value);
  const number = Number(exact);
  if (String(number) !== exact) throw invalid();
  if (positive && number <= 0) throw invalid();
  return number;
}

/** 수수료와 세금을 소수 자리까지 정확히 더한다. 모델이 더하지 않게 한다. */
function sum(left: unknown, right: unknown) {
  const parts = [decimalText(left), decimalText(right)];
  const scale = Math.max(
    ...parts.map((part) => part.split(".")[1]?.length ?? 0),
  );
  const scaled = parts.map((part) => {
    const [whole, fraction = ""] = part.split(".");
    return BigInt(`${whole}${fraction.padEnd(scale, "0")}`);
  });
  const total = (scaled[0]! + scaled[1]!).toString().padStart(scale + 1, "0");
  const whole = total.slice(0, total.length - scale);
  return amount(
    scale ? `${whole}.${total.slice(total.length - scale)}` : whole,
  );
}

function fee(item: Item) {
  const { fee: total, commission, tax } = item;
  const parts = [commission, tax].filter(
    (value) => value !== undefined && value !== null,
  );
  if (total !== undefined && total !== null) {
    if (parts.length) throw invalid();
    return amount(total);
  }
  if (!parts.length) return undefined;
  return parts.length === 2 ? sum(parts[0], parts[1]) : amount(parts[0]);
}

function offsetTime(value: unknown) {
  if (typeof value !== "string") throw invalid();
  const time = value.trim();
  if (!OFFSET_TIME.test(time) || Number.isNaN(Date.parse(time)))
    throw invalid();
  return time;
}

function object(value: unknown, fields: Set<string>): Item {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw invalid();
  // 계좌 번호처럼 계약 밖의 칸은 워크벤치로 보내지 않는다.
  if (!Object.keys(value).every((key) => fields.has(key))) throw invalid();
  return value as Item;
}

function items(value: unknown, max: number) {
  if (!Array.isArray(value) || value.length < 1 || value.length > max)
    throw invalid();
  return value;
}

/** 토스증권의 주문 하나를 워크벤치 체결 입력으로 옮긴다. 수수료와 세금은 `fee` 하나로 더한다. */
function tradeInput(value: unknown) {
  const item = object(value, TRADE_FIELDS);
  const ticker = text(item.ticker, 32)!;
  if (!TICKER.test(ticker)) throw invalid();
  const currency = text(item.currency, 3)!;
  if (!CURRENCY.test(currency)) throw invalid();
  const name = text(item.name, 100, false);
  const total = fee(item);
  return {
    broker:
      item.broker === undefined || item.broker === null
        ? "TOSS"
        : oneOf(item.broker, new Set(["TOSS", "OTHER"])),
    externalId: text(item.externalId, 100)!,
    side: oneOf(item.side, new Set(["BUY", "SELL"])),
    market: oneOf(item.market, MARKETS),
    ticker: ticker.toUpperCase(),
    ...(name === undefined ? {} : { name }),
    quantity: amount(item.quantity, true),
    price: amount(item.price),
    currency: currency.toUpperCase(),
    amount: amount(item.amount),
    ...(total === undefined ? {} : { fee: total }),
    executedAt: offsetTime(item.executedAt),
  };
}

function ids(value: unknown, max: number) {
  const list = items(value, max);
  if (!list.every((id) => typeof id === "string" && UUID.test(id)))
    throw invalid();
  return list as string[];
}

function evidence(value: unknown) {
  if (value === undefined || value === null) return undefined;
  const item = object(value, new Set(Object.keys(EVIDENCE_LIMITS)));
  const result: Record<string, string[]> = {};
  for (const [key, max] of Object.entries(EVIDENCE_LIMITS)) {
    const list = item[key];
    if (list === undefined || (Array.isArray(list) && !list.length)) {
      result[key] = [];
      continue;
    }
    if (key !== "urls") {
      result[key] = ids(list, max);
      continue;
    }
    const urls = items(list, max);
    if (!urls.every(isWebUrl)) throw invalid();
    result[key] = urls as string[];
  }
  return result;
}

function isWebUrl(value: unknown) {
  if (typeof value !== "string" || value.length > 2000) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:";
  } catch {
    return false;
  }
}

/** 제안 하나를 검사한다. `payload` 의 종류별 규칙은 워크벤치가 본다. */
function proposalInput(value: unknown) {
  const item = object(value, PROPOSAL_FIELDS);
  const payload = item.payload;
  if (!payload || typeof payload !== "object" || Array.isArray(payload))
    throw invalid();
  const attached = evidence(item.evidence);
  return {
    kind: oneOf(item.kind, KINDS),
    payload,
    rationale: text(item.rationale, 2000)!,
    ...(attached ? { evidence: attached } : {}),
    // 다시 올려도 같은 제안이 두 번 생기지 않게 이 도구는 키를 꼭 받는다.
    dedupeKey: text(item.dedupeKey, 100)!,
  };
}

const tradeSchema = z.looseObject({
  broker: z.string().nullable().optional(),
  externalId: z.string(),
  side: z.string(),
  market: z.string(),
  ticker: z.string(),
  name: z.string().nullable().optional(),
  quantity: z.union([z.string(), z.number()]),
  price: z.union([z.string(), z.number()]),
  currency: z.string(),
  amount: z.union([z.string(), z.number()]),
  commission: z.union([z.string(), z.number()]).nullable().optional(),
  tax: z.union([z.string(), z.number()]).nullable().optional(),
  fee: z.union([z.string(), z.number()]).nullable().optional(),
  executedAt: z.string(),
});

const proposalSchema = z.looseObject({
  kind: z.string(),
  payload: z.record(z.string(), z.unknown()),
  rationale: z.string(),
  evidence: z
    .looseObject({
      nodeIds: z.array(z.string()).optional(),
      tradeIds: z.array(z.string()).optional(),
      urls: z.array(z.string()).optional(),
    })
    .optional(),
  // 꼭 받지만 빠졌을 때 SDK 의 글 대신 이 커넥터의 오류 코드로 끝내려고 선택으로 둔다.
  dedupeKey: z.string().optional(),
});

export function registerWriteTools(
  register: RegisterTool,
  workbench: Workbench,
) {
  const read = { readOnlyHint: true };
  // 같은 체결과 같은 `dedupeKey` 는 다시 보내도 새로 생기지 않는다.
  const write = {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: true,
  };

  register(
    "record_trades",
    { trades: z.array(tradeSchema) },
    write,
    ({ trades }) =>
      workbench.post("/trades", {
        trades: items(trades, TRADES_MAX).map(tradeInput),
      }),
  );

  register(
    "list_my_trades",
    {
      ticker: z.string().default(""),
      from: z.string().default(""),
      to: z.string().default(""),
      ...pageSchema,
    },
    read,
    ({ ticker, from, to, page, size }) => {
      const query = new URLSearchParams();
      const code = ticker.trim();
      if (code) {
        if (!TICKER.test(code)) throw invalid();
        query.set("ticker", code);
      }
      if (from.trim()) query.set("from", offsetTime(from));
      if (to.trim()) query.set("to", offsetTime(to));
      paging(query, page, size);
      return workbench.get("/trades", query);
    },
  );

  register(
    "submit_proposals",
    { proposals: z.array(proposalSchema) },
    write,
    ({ proposals }) =>
      workbench.post("/proposals", {
        proposals: items(proposals, PROPOSALS_MAX).map(proposalInput),
      }),
  );

  register(
    "list_my_proposals",
    { status: z.string().default(""), ...pageSchema },
    read,
    ({ status, page, size }) => {
      const query = new URLSearchParams();
      if (status.trim()) query.set("status", oneOf(status, STATUSES));
      paging(query, page, size);
      return workbench.get("/proposals", query);
    },
  );
}
