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
    get_exchange_rate:
      "KRW와 USD 사이의 참고용 표시 환율 한 건을 읽습니다. baseCurrency와 quoteCurrency는 서로 다른 KRW 또는 USD이고, 선택 dateTime은 시간대가 있는 ISO 시각입니다. 생략하면 현재 유효 환율입니다. result의 환율, 중간 환율, 변동과 validFrom/validUntil을 원문 그대로 반환하며 metadata는 빈 객체입니다. 1분 주기로 갱신되는 표시값으로 주문의 실제 거래 환율을 보장하지 않습니다. 주문하거나 환전하지 않습니다.",
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
