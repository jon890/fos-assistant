import { z } from "zod";
import { NAME_MAX_CHARS, QUOTE_SYMBOLS_MAX, SYMBOL } from "./constants.ts";
import { TossinvestError, truncateCodePoints } from "./errors.ts";
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
  Array.isArray(value?.result)
    ? value.result.filter((row: unknown) => row && typeof row === "object")
    : [];

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
  register("list_accounts", {}, { readOnlyHint: true }, async () => ({
    accounts: rows(await client.request("/api/v1/accounts"))
      .filter(
        (row: any) =>
          (typeof row.accountSeq === "string" ||
            typeof row.accountSeq === "number") &&
          String(row.accountSeq) !== "",
      )
      .map((row: any) => {
        const type = typeof row.accountType === "string" ? row.accountType : "";
        const digits = String(row.accountNo ?? "").replace(/\D/g, "");
        return {
          account_seq: String(row.accountSeq),
          account_type: type,
          label: `${ACCOUNT_TYPE_LABELS[type] ?? "기타"} ****${digits.slice(-4)}`,
        };
      }),
  }));
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
        quotes: rows(prices)
          .filter((row: any) => typeof row.symbol === "string")
          .map((row: any) => ({
            symbol: row.symbol,
            name: names.get(row.symbol) ?? null,
            last_price: row.lastPrice ?? null,
            currency: typeof row.currency === "string" ? row.currency : null,
            timestamp: row.timestamp ?? null,
          })),
      };
    },
  );
}
