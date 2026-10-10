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
    list_accounts:
      "연결한 토스증권 계좌의 순번과 유형, 끝 네 자리를 읽습니다.",
    get_quotes:
      "종목 코드나 티커 200개까지의 현재가와 이름을 읽습니다. 예: symbols 에 005930,AAPL 을 넣습니다.",
    get_holdings: "연결한 계좌의 보유 종목과 평가 금액, 손익을 읽습니다.",
    get_sellable_quantity: "연결한 계좌의 종목별 매도 가능 수량을 읽습니다. symbol은 한 종목입니다.",
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
