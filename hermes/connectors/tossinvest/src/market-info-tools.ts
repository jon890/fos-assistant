import { z } from "zod";
import { envelopeMetadata } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { TossinvestError } from "./errors.ts";
import type { RegisterTool } from "./tool-registration.ts";

/** 날짜를 계산하거나 세션을 합치지 않고 공식 시장 정보 전체를 보존한다. */
export function registerMarketInfoTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  register(
    "get_exchange_rate",
    {
      baseCurrency: z.enum(["KRW", "USD"]),
      quoteCurrency: z.enum(["KRW", "USD"]),
      dateTime: z.iso
        .datetime({ offset: true })
        .regex(/T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/)
        .optional(),
    },
    { readOnlyHint: true },
    async ({ baseCurrency, quoteCurrency, dateTime }) => {
      if (baseCurrency === quoteCurrency)
        throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
      const query = new URLSearchParams({ baseCurrency, quoteCurrency });
      if (dateTime !== undefined) query.set("dateTime", dateTime);
      // 참고용 표시 환율이다. 주문에 적용될 거래 환율을 보장하지 않는다.
      const envelope = await client.request("/api/v1/exchange-rate", { query });
      return { result: envelope.result, metadata: envelopeMetadata(envelope) };
    },
  );
  register(
    "get_market_calendar",
    {
      market: z.enum(["KR", "US"]),
      date: z.iso.date().optional(),
    },
    { readOnlyHint: true },
    async ({ market, date }) => {
      const query = new URLSearchParams();
      if (date !== undefined) query.set("date", date);
      const path =
        market === "KR"
          ? "/api/v1/market-calendar/KR"
          : "/api/v1/market-calendar/US";
      const envelope = await client.request(path, { query });
      return { result: envelope.result, metadata: envelopeMetadata(envelope) };
    },
  );
}
