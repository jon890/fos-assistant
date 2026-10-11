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
    get_orderbook:
      "symbol 한 종목의 매수·매도 호가와 잔량, 통화, 데이터 시각을 조회합니다. 국내 종목 코드(005930, 0101N0)나 미국 티커(AAPL, BRK.B)를 넣습니다. 가격과 수량은 decimal 문자열이며 데이터 시각은 null일 수 있습니다. 조회 시점의 REST 결과로 실시간 구독이나 주문 기능은 없습니다.",
    get_trades:
      "symbol 한 종목의 당일 최근 체결가·수량·시각·통화를 조회합니다. 국내 종목 코드나 미국 티커를 넣습니다. count는 기본 50, 1~50의 정수입니다. 가격과 수량은 decimal 문자열이며 체결이 없으면 빈 배열입니다. 조회 시점의 REST 결과로 실시간 구독이나 주문 기능은 없습니다.",
    get_price_limits:
      "symbol 한 종목의 당일 상한가·하한가와 통화, 데이터 시각을 조회합니다. 국내 종목 코드나 미국 티커를 넣습니다. 가격은 decimal 문자열이며 미국처럼 가격제한이 없는 시장에서는 null입니다. 조회 시점의 REST 결과로 실시간 구독이나 주문 기능은 없습니다.",
    get_candles:
      "symbol 한 종목의 OHLCV 캔들을 최신순으로 한 페이지 조회합니다. 국내 종목 코드나 미국 티커와 필수 interval(1m 분봉, 1d 일봉)을 넣습니다. count는 기본 100, 1~200의 정수이고 adjusted는 기본 true이며 false도 가능합니다. before는 타임존을 포함한 ISO 8601 시각이며 해당 시각을 포함해 이전 봉을 읽습니다. 예: 2026-03-25T09:00:00+09:00. nextBefore를 다음 호출의 before로 그대로 넣으며 마지막 페이지는 null입니다. 오프셋의 +는 커넥터가 인코딩합니다. 가격과 수량은 decimal 문자열입니다. 전체 페이지 자동 수집, 실시간 구독, 주문 기능은 없습니다.",
    get_exchange_rate:
      "KRW와 USD 사이의 참고용 표시 환율 한 건을 읽습니다. baseCurrency와 quoteCurrency는 서로 다른 KRW 또는 USD이고, 선택 dateTime은 초와 시간대가 있는 RFC 3339 시각입니다. 생략하면 현재 유효 환율입니다. result의 환율, 중간 환율, 변동과 validFrom/validUntil을 원문 그대로 반환하며 metadata는 빈 객체입니다. 1분 주기로 갱신되는 표시값으로 주문의 실제 거래 환율을 보장하지 않습니다. 주문하거나 환전하지 않습니다.",
    get_market_calendar:
      "market에 KR 또는 US를 넣어 국내 또는 미국 시장의 전일·당일·익일 3영업일 운영 일정을 읽습니다. 선택 date는 YYYY-MM-DD이며 미국 시장은 미국 현지 날짜입니다. 모든 응답 시각은 KST(+09:00) 원문입니다. 국내는 KRX+NXT 통합 세션이며 장전·장후 시간외종가는 제외됩니다. 미국은 dayMarket, preMarket, regularMarket, afterMarket 4세션입니다. result의 날짜·세션·휴장 null을 그대로 반환하며 metadata는 빈 객체입니다. 날짜를 계산하거나 세션을 합치지 않으며 주문하지 않습니다.",
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
