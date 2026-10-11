import { z } from "zod";
import { envelopeMetadata, operationFor } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { SYMBOL } from "./constants.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { officialFields } from "./official-response.ts";

/** 국내 여부는 API가 판정한다. 공식 pattern을 유지하되 URL이 정규화하는 점 segment는 막는다. */
export function registerStockStatisticsTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  const schema = {
    symbol: z
      .string()
      .regex(SYMBOL)
      .refine(
        (value) => value.trim() === value && value !== "." && value !== "..",
      )
      .describe("국내(KR) 종목 symbol. 영문, 숫자, 점, 하이픈을 허용한다."),
    count: z
      .number()
      .int()
      .min(1)
      .max(100)
      .default(10)
      .describe("최신순으로 읽을 일별 기록 수. 1~100, 기본 10."),
    until: z.iso
      .date()
      .length(10)
      .optional()
      .describe(
        "해당 날짜를 포함한 조회 기준일(YYYY-MM-DD). 다음 페이지에는 nextUntil을 그대로 넣는다.",
      ),
  };

  // 투자자별 거래량은 KRX·NXT 통합이며 등록외국인, 기관 7개 분류, 보유와 CFD를 포함한다.
  // 프로그램 거래량은 KRX만 집계하고 차익·비차익을 구분한다. 두 통계의 음수 순매수는 순매도다.
  // 공매도 금액은 KRW, 비중은 소수 비율이다. 분모 부재의 null과 분모 0의 문자열 0을 보존한다.
  // 신용의 nullable marginLoan·stockLoan은 개인 신용거래다. 기관 대차의 KRW 잔고 금액과 구분한다.
  // 투자자는 장중 잠정치·당일 저녁 확정치·CFD T+1·외국인 보유 다음날 오전 갱신이 섞인다.
  // 프로그램은 장 종료 전 바뀔 수 있다. 공매도·대차는 당일 저녁, 신용은 다음 영업일 새벽에 반영된다.
  for (const [name, segment] of [
    ["get_stock_investor_trading", "investor-trading"],
    ["get_stock_program_trades", "program-trades"],
    ["get_stock_short_selling", "short-selling"],
    ["get_stock_credit_trades", "credit-trades"],
    ["get_stock_securities_lending", "securities-lending"],
  ] as const) {
    register(
      name,
      schema,
      { readOnlyHint: true },
      async ({ symbol, count, until }) => {
        const query = new URLSearchParams({ count: String(count) });
        if (until !== undefined) query.set("until", until);
        const path = `/api/v1/stocks/${encodeURIComponent(symbol)}/${segment}`;
        const envelope = await client.request(path, { query });
        // 한 페이지 전체를 반환한다. 수치 문자열, null과 optional을 보존하며 다음 페이지는 호출자가 읽는다.
        return {
          result: officialFields(
            envelope,
            operationFor("GET", path).responses["200"],
          ).result,
          metadata: envelopeMetadata(envelope),
        };
      },
    );
  }
}
