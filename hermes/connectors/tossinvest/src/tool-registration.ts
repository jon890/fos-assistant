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
  const statisticsPage =
    " 국내(KR) 종목의 일별 통계 한 페이지입니다. symbol은 영문·숫자·점·하이픈을 허용하며 미국 종목은 unsupported-market 오류입니다. count는 1~100의 정수이며 기본 10입니다. until(YYYY-MM-DD)은 해당 날짜를 포함하며 다음 페이지에는 nextUntil을 그대로 넣습니다. decimal 문자열과 null을 그대로 반환합니다.";
  const descriptions: Record<string, string> = {
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
    get_stock_investor_trading:
      "KRX와 NXT를 합친 투자자별 매매 수량(주)을 읽습니다. 기관 7개 분류, 등록외국인과 외국인 보유, CFD를 구분합니다. 음수 순매수는 순매도입니다. 장중 잠정치의 개인·기관 세부·기타법인·외국인 보유·CFD는 null일 수 있습니다. 당일 저녁 확정치, CFD 다음 영업일 새벽, 외국인 보유 다음 영업일 오전 재갱신을 구분합니다. 보유·CFD 비중은 소수 비율이며 updatedAt은 기록 전체의 마지막 갱신 시각입니다." +
      statisticsPage,
    get_stock_program_trades:
      "KRX만 집계한 프로그램 거래량(주)을 읽습니다. NXT는 포함하지 않습니다. 차익 arbitrage와 비차익 nonArbitrage를 구분하며 음수 순매수는 순매도입니다. 당일 값은 장 종료 전 바뀔 수 있습니다. 거래대금과 updatedAt은 제공하지 않습니다." +
      statisticsPage,
    get_stock_short_selling:
      "공매도 거래량(주)과 거래대금(KRW), 소수 비율의 비중을 읽습니다. 비중의 분모는 정규장 외 세션을 포함한 당일 누적 값입니다. 분모가 없으면 null, 0이면 문자열 0입니다. 일별 확정치는 해당 일자 저녁에 반영됩니다." +
      statisticsPage,
    get_stock_credit_trades:
      "자기신용과 유통금융을 합친 개인 신용거래를 읽습니다. marginLoan과 stockLoan은 각각 null일 수 있습니다. 수량은 주, balanceRate와 tradingRate는 소수 비율입니다. 개인 신용대주는 기관 대차와 다릅니다. 다음 영업일 새벽에 반영되므로 최신 기록은 전 영업일입니다." +
      statisticsPage,
    get_stock_securities_lending:
      "기관 투자자 사이의 주식 대여·차입을 읽습니다. 체결·상환·잔고 수량은 주, 잔고 금액은 KRW입니다. 개인 신용대주와 구분합니다. 일별 확정치는 해당 일자 저녁에 반영됩니다." +
      statisticsPage,
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
