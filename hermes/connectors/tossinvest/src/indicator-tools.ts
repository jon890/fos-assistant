import { z } from "zod";
import { envelopeMetadata } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { TossinvestError } from "./errors.ts";
import type { RegisterTool } from "./tool-registration.ts";

const symbols = [
  "KOSPI",
  "KOSDAQ",
  "KR_BOND_2Y",
  "KR_BOND_3Y",
  "KR_BOND_5Y",
  "KR_BOND_10Y",
  "KR_BOND_20Y",
  "KR_BOND_30Y",
] as const;
const symbolSchema = z
  .enum(symbols)
  .describe("지수는 point, 국채는 금리 percent입니다. 3.25는 3.25%입니다.");

export function registerIndicatorTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  register(
    "get_market_indicator_prices",
    {
      symbols: z
        .string()
        .regex(/^[A-Za-z0-9_,]+$/)
        .refine((value) => {
          const items = value.split(",");
          return (
            items.length <= 200 &&
            items.every((item) =>
              symbols.includes(item as (typeof symbols)[number]),
            )
          );
        })
        .describe(
          "KOSPI/KOSDAQ와 KR_BOND_2Y/3Y/5Y/10Y/20Y/30Y를 쉼표로 나눕니다. 지수는 point, 국채 금리는 percent입니다.",
        ),
    },
    { readOnlyHint: true },
    async ({ symbols }) => {
      const envelope = await client.request(
        "/api/v1/market-indicators/prices",
        { query: new URLSearchParams({ symbols }) },
      );
      return { result: envelope.result, metadata: envelopeMetadata(envelope) };
    },
  );
  register(
    "get_market_indicator_candles",
    {
      symbol: symbolSchema,
      interval: z
        .enum(["1m", "1d"])
        .describe("분봉은 지수만, 국채는 일봉만 지원합니다."),
      count: z.number().int().min(1).max(200).default(100),
      before: z.iso
        .datetime({ offset: true })
        .optional()
        .describe(
          "다음 페이지에는 nextBefore를 그대로 넣습니다. null이면 마지막입니다.",
        ),
    },
    { readOnlyHint: true },
    async ({ symbol, interval, count, before }) => {
      if (symbol.startsWith("KR_BOND_") && interval !== "1d")
        throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
      const query = new URLSearchParams({ interval, count: String(count) });
      if (before !== undefined) query.set("before", before);
      const envelope = await client.request(
        `/api/v1/market-indicators/${encodeURIComponent(symbol)}/candles`,
        { query },
      );
      return {
        result: envelope.result,
        metadata: envelopeMetadata(envelope),
        nextBefore: envelope.result.nextBefore,
      };
    },
  );
  register(
    "get_market_indicator_investor_trading",
    {
      symbol: z.enum(["KOSPI", "KOSDAQ"]),
      interval: z
        .enum(["1d", "1w", "1mo", "1y"])
        .describe(
          "KRW 매매대금입니다. 외국인은 등록·미등록 합계, 기관은 세부 7개 분류의 합계입니다.",
        ),
      count: z.number().int().min(1).max(100).default(10),
      until: z.iso
        .date()
        .optional()
        .describe(
          "다음 페이지에는 nextUntil을 그대로 넣습니다. null이면 마지막입니다.",
        ),
    },
    { readOnlyHint: true },
    async ({ symbol, interval, count, until }) => {
      const query = new URLSearchParams({ interval, count: String(count) });
      if (until !== undefined) query.set("until", until);
      const envelope = await client.request(
        `/api/v1/market-indicators/${encodeURIComponent(symbol)}/investor-trading`,
        { query },
      );
      return {
        result: envelope.result,
        metadata: envelopeMetadata(envelope),
        nextUntil: envelope.result.nextUntil,
      };
    },
  );
}
