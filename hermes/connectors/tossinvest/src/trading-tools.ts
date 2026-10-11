import { z } from "zod";
import { isDeepStrictEqual } from "node:util";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { TossinvestError } from "./errors.ts";
import { claimApproval, takeApproval } from "./execution-guard.ts";
import { prepareTrade, readExpectedOrder, orderPath } from "./prepare-trade.ts";
import {
  EXECUTION_SCHEMAS,
  TRADE_OPERATIONS,
  tradingBody,
  UNKNOWN,
  reject,
  type TradeTool,
} from "./trading-body.ts";

/** 운영 server에서는 아직 호출하지 않는다. 실제 등록은 테스트 entry만 소유한다. */
export function registerTradingTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  const authority = takeApproval(client.env);
  register(
    "prepare_trade",
    {
      v: z.literal(1),
      tool: z.enum(["create_order", "modify_order", "cancel_order"]),
      args: z.record(z.string(), z.unknown()),
    },
    { readOnlyHint: true },
    async ({ tool, args }) => prepareTrade(client, tool, args),
  );

  for (const tool of Object.keys(TRADE_OPERATIONS) as TradeTool[]) {
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
