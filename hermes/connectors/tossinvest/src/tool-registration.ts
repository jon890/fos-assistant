import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { guard } from "./errors.ts";
import { z } from "zod";

export type RegisterTool = (
  name: string,
  schema: any,
  annotations: any,
  run: (input: any) => Promise<unknown>,
) => void;

export function createToolRegistration(server: McpServer) {
  const descriptions: Record<string, string> = {
    get_rankings:
      "국내·미국 주식의 거래대금·거래량·급상승·급하락 랭킹을 읽습니다. 시장 전체와 토스증권 체결 기준을 구분하며 TOP 랭킹은 realtime을 지원하지 않습니다.",
    get_market_indicator_prices:
      "코스피·코스닥 지수와 한국 국채 6종의 현재 시세를 읽습니다. 지수는 point, 국채 금리는 percent이며 3.25는 3.25%입니다.",
    get_market_indicator_candles:
      "국내 지수와 국채 금리의 캔들을 읽습니다. 국채는 일봉만 지원하며 nextBefore를 다음 before에 넣고 null이면 끝납니다.",
    get_market_indicator_investor_trading:
      "코스피·코스닥의 투자자별 KRW 매매대금을 읽습니다. nextUntil을 다음 until에 넣고 null이면 끝납니다. 외국인은 등록·미등록 합계, 기관은 세부 7개 분류의 합계입니다.",
    list_accounts: "연결한 토스증권 계좌의 순번과 유형, 끝 네 자리를 읽습니다.",
    get_quotes:
      "종목 코드나 티커 200개까지의 현재가와 이름을 읽습니다. 예: symbols 에 005930,AAPL 을 넣습니다.",
    get_holdings: "연결한 계좌의 보유 종목과 평가 금액, 손익을 읽습니다.",
    get_sellable_quantity:
      "연결한 계좌의 종목별 매도 가능 수량을 읽습니다. symbol은 한 종목입니다.",
    get_buying_power:
      "통화별 주문 가능 현금을 읽습니다. symbol 을 주면 그 종목의 매도 가능 수량도 읽습니다.",
    list_orders:
      "미체결(OPEN)이나 끝난(CLOSED) 주문을 기간으로 읽습니다. CLOSED는 기본 20건과 최대 100건의 cursor 페이지를, OPEN은 전체 주문을 돌려줍니다.",
  };
  const register: RegisterTool = (
    name: string,
    schema: any,
    annotations: any,
    run: (input: any) => Promise<unknown>,
  ) =>
    server.registerTool(
      name,
      {
        description: `${descriptions[name]} 필요한 인자와 결과를 확인한 뒤 사용합니다.`,
        inputSchema: z.strictObject(schema),
        annotations,
      },
      (input: any) => guard(() => run(input)),
    );
  return register;
}
