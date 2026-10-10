import { z } from "zod";
import { NAME_MAX_CHARS, QUOTE_SYMBOLS_MAX, SYMBOL } from "./constants.ts";
import { envelopeMetadata } from "./api-contract.ts";
import { TossinvestError } from "./errors.ts";
import { decimalValue, serviceValue, truncateCodePoints } from "./values.ts";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";

/** API 의 `accountType` 을 사용자가 읽는 이름으로 옮긴다. 표에 없는 유형은 「기타」 다. */
const ACCOUNT_TYPE_LABELS: Record<string, string> = {
  BROKERAGE: "종합매매",
  OVERSEAS_DERIVATIVES: "해외파생",
  PENSION_SAVINGS: "연금저축",
  RESHORING_INVESTMENT: "국내복귀투자",
};

const rows = (value: any): any[] =>
  value.result;

/** 쉼표로 나누고 공백을 뗀 뒤 비지 않은 것만 남긴다. 개수와 형식이 틀리면 요청 없이 거절한다. */
export function parseSymbols(value: string): string[] {
  const symbols = value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  if (
    symbols.length < 1 ||
    symbols.length > QUOTE_SYMBOLS_MAX ||
    symbols.some((symbol) => !SYMBOL.test(symbol))
  )
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return symbols;
}

export function registerReadTools(register: RegisterTool, client: Tossinvest) {
  // 확인 도구이자 선택지 도구다. 계좌가 아직 없으므로 계좌 헤더를 보내지 않는다.
  register("list_accounts", {}, { readOnlyHint: true }, async () => {
    const envelope = await client.request("/api/v1/accounts");
    const safe = rows(envelope).map((row: any) => ({ ...row, accountNo: `****${row.accountNo.replace(/\D/g, "").slice(-4)}` }));
    return {
      result: safe, metadata: envelopeMetadata(envelope),
      accounts: safe.map((row: any) => {
        const type = serviceValue(row.accountType);
        const known = typeof type === "string" ? ACCOUNT_TYPE_LABELS[type] : undefined;
        return {
          account_seq: row.accountSeq,
          account_type: type,
          label: `${known ?? "기타"} ${row.accountNo}`,
        };
      }),
    };
  });
  register(
    "get_quotes",
    { symbols: z.string().default("") },
    { readOnlyHint: true },
    async ({ symbols }) => {
      const query = new URLSearchParams({
        symbols: parseSymbols(symbols).join(","),
      });
      // 계좌와 무관한 조회라 계좌 헤더를 보내지 않는다. 두 요청은 같은 symbols 로 나란히 간다.
      const [prices, stocks] = await Promise.all([
        client.request("/api/v1/prices", { query }),
        client.request("/api/v1/stocks", { query }),
      ]);
      const names = new Map<string, string>();
      for (const row of rows(stocks))
        if (typeof row.symbol === "string" && typeof row.name === "string")
          names.set(row.symbol, truncateCodePoints(row.name, NAME_MAX_CHARS));
      return {
        result: { prices: prices.result, stocks: stocks.result },
        metadata: { prices: envelopeMetadata(prices), stocks: envelopeMetadata(stocks) },
        quotes: rows(prices)
          .map((row: any) => ({
            symbol: serviceValue(row.symbol),
            name: names.get(row.symbol) ?? null,
            last_price: decimalValue(row.lastPrice),
            currency: serviceValue(row.currency),
            timestamp: serviceValue(row.timestamp),
          })),
      };
    },
  );
}
