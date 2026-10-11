import { z } from "zod";
import { SCHEMAS, envelopeMetadata } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { TossinvestError } from "./errors.ts";
import { officialFields } from "./official-response.ts";
import type { RegisterTool } from "./tool-registration.ts";

export function registerRankingTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  register(
    "get_rankings",
    {
      type: z
        .enum([
          "MARKET_TRADING_AMOUNT",
          "MARKET_TRADING_VOLUME",
          "TOP_GAINERS",
          "TOP_LOSERS",
          "TOSS_SECURITIES_TRADING_AMOUNT",
          "TOSS_SECURITIES_TRADING_VOLUME",
        ])
        .describe(
          "TOSS_SECURITIES는 토스증권 체결, 나머지는 시장 전체 기준입니다. TOP의 기준가·등락률은 기간 시작값, 나머지는 전일 값입니다.",
        ),
      marketCountry: z.enum(["KR", "US"]),
      duration: z.enum(["realtime", "1d", "1w", "1mo", "3mo", "6mo", "1y"]),
      excludeInvestmentCaution: z.boolean().default(false),
      count: z.number().int().min(1).max(100).default(100),
    },
    { readOnlyHint: true },
    async ({
      type,
      marketCountry,
      duration,
      excludeInvestmentCaution,
      count,
    }) => {
      if (
        duration === "realtime" &&
        (type === "TOP_GAINERS" || type === "TOP_LOSERS")
      )
        throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
      const query = new URLSearchParams({
        type,
        marketCountry,
        duration,
        excludeInvestmentCaution: String(excludeInvestmentCaution),
        count: String(count),
      });
      const envelope = await client.request("/api/v1/rankings", { query });
      return {
        result: officialFields(envelope.result, SCHEMAS.RankingResponse),
        metadata: envelopeMetadata(envelope),
      };
    },
  );
}
