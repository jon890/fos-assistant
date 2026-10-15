import { z } from "zod";
import { isDeepStrictEqual } from "node:util";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { TossinvestError } from "./errors.ts";
import { claimApproval, takeApproval } from "./execution-guard.ts";
import { prepareTrade, readExpectedOrder, orderPath } from "./prepare-trade.ts";
import {
  EXECUTION_SCHEMAS,
  OPEN_TRADE_TOOLS,
  tradingBody,
  UNKNOWN,
  reject,
  type TradeTool,
} from "./trading-body.ts";

/**
 * 준비 도구와 `tools`의 실행 도구를 등록한다. 운영 server는 기본값인 주문과 취소만 연다.
 * 정정은 테스트 entry만 명시해 등록한다.
 */
export function registerTradingTools(
  register: RegisterTool,
  client: Tossinvest,
  tools: readonly [TradeTool, ...TradeTool[]] = OPEN_TRADE_TOOLS,
) {
  const authority = takeApproval(client.env);
  register(
    "prepare_trade",
    {
      v: z.literal(1),
      tool: z.enum(tools),
      args: z.record(z.string(), z.unknown()),
    },
    { readOnlyHint: true },
    async ({ tool, args }) => prepareTrade(client, tool, args),
  );

  for (const tool of tools) {
    register(
      tool,
      EXECUTION_SCHEMAS[tool].shape,
      {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
      },
      async (input) => {
        const beforeSend = await claimApproval(
          authority,
          client.env,
          tool,
          input,
        );
        const body = tradingBody(tool, input);
        let path = "/api/v1/orders";
        if (tool !== "create_order") {
          const current = await readExpectedOrder(client, input.orderId);
          if (!isDeepStrictEqual(current, input.expected_order)) reject();
          path = `${orderPath(input.orderId)}/${tool === "modify_order" ? "modify" : "cancel"}`;
        }
        beforeSend();
        const response = await client.financial("POST", path, body, beforeSend);
        // 정정·취소는 새 ID를 요구한다. 계약 위반 2xx도 재전송 없이 UNKNOWN이다.
        if (
          tool !== "create_order" &&
          response.result.orderId === input.orderId
        )
          throw new TossinvestError(UNKNOWN);
        const returned = response.result.clientOrderId;
        if (
          tool === "create_order" &&
          returned !== undefined &&
          returned !== null &&
          returned !== input.clientOrderId
        )
          throw new TossinvestError(UNKNOWN);
        // 서비스 부가 필드와 권한 원문을 결과에 싣지 않는다. 정정·취소의 새 ID는 보존한다.
        return {
          orderId: response.result.orderId,
          ...(tool === "create_order" && returned !== undefined
            ? { clientOrderId: returned }
            : {}),
        };
      },
    );
  }
}
