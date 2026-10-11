import { z } from "zod";
import { SYMBOL } from "./constants.ts";
import { envelopeMetadata } from "./api-contract.ts";
import { TossinvestError } from "./errors.ts";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";

function symbolQuery(symbol: string): URLSearchParams {
  if (!SYMBOL.test(symbol))
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return new URLSearchParams({ symbol });
}

function countValue(count: number | undefined, fallback: number, max: number) {
  const value = count ?? fallback;
  if (!Number.isInteger(value) || value < 1 || value > max)
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return String(value);
}

/** 공식 result를 그대로 싣는다. decimal 문자열과 null, 배열 순서를 보존한다. */
export function registerMarketDataTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  const read = async (path: string, query: URLSearchParams) => {
    // 시세와 캔들은 계좌와 무관하며 그룹별 큐와 응답 검사는 공용 client가 맡는다.
    const envelope = await client.request(path, { query });
    return { result: envelope.result, metadata: envelopeMetadata(envelope) };
  };

  register(
    "get_orderbook",
    { symbol: z.string() },
    { readOnlyHint: true },
    async ({ symbol }) => read("/api/v1/orderbook", symbolQuery(symbol)),
  );
  register(
    "get_trades",
    { symbol: z.string(), count: z.number().optional() },
    { readOnlyHint: true },
    async ({ symbol, count }) => {
      const query = symbolQuery(symbol);
      query.set("count", countValue(count, 50, 50));
      return read("/api/v1/trades", query);
    },
  );
  register(
    "get_price_limits",
    { symbol: z.string() },
    { readOnlyHint: true },
    async ({ symbol }) => read("/api/v1/price-limits", symbolQuery(symbol)),
  );
  register(
    "get_candles",
    {
      symbol: z.string(),
      interval: z.string(),
      count: z.number().optional(),
      before: z.string().optional(),
      adjusted: z.boolean().optional(),
    },
    { readOnlyHint: true },
    async ({ symbol, interval, count, before, adjusted }) => {
      const query = symbolQuery(symbol);
      if (interval !== "1m" && interval !== "1d")
        throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
      query.set("interval", interval);
      query.set("count", countValue(count, 100, 200));
      query.set("adjusted", String(adjusted ?? true));
      if (before !== undefined) {
        if (!z.iso.datetime({ offset: true }).safeParse(before).success)
          throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
        // inclusive 상한과 nextBefore를 바꾸지 않고, + 인코딩은 URLSearchParams에 맡긴다.
        query.set("before", before);
      }
      return read("/api/v1/candles", query);
    },
  );
}
