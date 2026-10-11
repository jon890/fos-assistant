import { z } from "zod";
import { envelopeMetadata } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { SYMBOL } from "./constants.ts";
import { parseSymbols } from "./read-tools.ts";
import type { RegisterTool } from "./tool-registration.ts";

/** 공식 REST 1.2.24의 종목 참조 조회다. 전체 목록에 페이지나 공용 cache를 만들지 않는다. */
export function registerStockTools(register: RegisterTool, client: Tossinvest) {
  register(
    "get_stocks",
    { symbols: z.string() },
    { readOnlyHint: true },
    async ({ symbols }) => {
      const envelope = await client.request("/api/v1/stocks", {
        query: new URLSearchParams({
          symbols: parseSymbols(symbols).join(","),
        }),
      });
      return { result: envelope.result, metadata: envelopeMetadata(envelope) };
    },
  );
  register(
    "list_stocks",
    {
      market: z.enum([
        "KOSPI",
        "KOSDAQ",
        "NYSE",
        "NASDAQ",
        "AMEX",
        "KR_ETC",
        "US_ETC",
      ]),
      status: z.enum(["SCHEDULED", "ACTIVE", "DELISTED"]).optional(),
      securityType: z
        .enum([
          "STOCK",
          "FOREIGN_STOCK",
          "DEPOSITARY_RECEIPT",
          "INFRASTRUCTURE_FUND",
          "REIT",
          "ETF",
          "FOREIGN_ETF",
          "ETN",
          "STOCK_WARRANTS",
        ])
        .optional(),
      commonShare: z.boolean().optional(),
    },
    { readOnlyHint: true },
    async ({ market, status, securityType, commonShare }) => {
      const query = new URLSearchParams({ market });
      // status를 생략하면 공식 API가 ACTIVE를 적용한다. false도 필터로 전송한다.
      if (status !== undefined) query.set("status", status);
      if (securityType !== undefined) query.set("securityType", securityType);
      if (commonShare !== undefined)
        query.set("commonShare", String(commonShare));
      const envelope = await client.request("/api/v1/stocks/all", { query });
      return { result: envelope.result, metadata: envelopeMetadata(envelope) };
    },
  );
  register(
    "get_stock_warnings",
    {
      symbol: z
        .string()
        .regex(SYMBOL)
        .refine((value) => value.trim() === value),
    },
    { readOnlyHint: true },
    async ({ symbol }) => {
      // exchange는 물리적 거래소 코드다. 상장 시장인 market으로 옮기지 않는다.
      const envelope = await client.request(
        `/api/v1/stocks/${encodeURIComponent(symbol)}/warnings`,
      );
      return { result: envelope.result, metadata: envelopeMetadata(envelope) };
    },
  );
}
