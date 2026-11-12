import { z } from "zod";
import { isDeepStrictEqual } from "node:util";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { TossinvestError } from "./errors.ts";
import { claimApproval, takeApproval } from "./execution-guard.ts";
import {
  CONDITIONAL_EXECUTION_SCHEMAS,
  CONDITIONAL_MODEL_SCHEMAS,
  CONDITIONAL_TOOLS,
  checkOcoPrice,
  conditionalBody,
} from "./conditional-body.ts";
import {
  conditionalPath,
  prepareConditionalTrade,
  readExpectedConditionalOrder,
  readLastPrice,
} from "./prepare-conditional.ts";
import { reject, UNKNOWN } from "./trading-body.ts";

/**
 * 조건주문 준비 도구와 세 쓰기 도구를 등록한다. 테스트 entry 만 부른다.
 * 운영 createTossinvestServer 와 assistant.json 은 이 도구를 등록하거나 선언하지 않는다.
 */
export function registerConditionalTradingTools(
  register: RegisterTool,
  client: Tossinvest,
): void {
  const authority = takeApproval(client.env);
  register(
    "prepare_conditional_trade",
    {
      v: z.literal(1),
      tool: z.enum(CONDITIONAL_TOOLS),
      args: z.union(
        CONDITIONAL_TOOLS.map(
          (tool) => CONDITIONAL_MODEL_SCHEMAS[tool],
        ) as unknown as [z.ZodType, ...z.ZodType[]],
      ),
    },
    { readOnlyHint: true },
    async ({ tool, args }) => prepareConditionalTrade(client, tool, args),
  );

  for (const tool of CONDITIONAL_TOOLS) {
    register(
      tool,
      CONDITIONAL_EXECUTION_SCHEMAS[tool].shape,
      { readOnlyHint: false, destructiveHint: true, idempotentHint: false },
      async (input) => {
        // 원문과 SDK 인자를 변환 전에 대조하고 claim 을 한 번만 부른다.
        const approval = await claimApproval(
          authority,
          client.env,
          tool,
          input,
        );
        const body = conditionalBody(tool, input);
        const cancel = tool === "cancel_conditional_order";
        let path = "/api/v1/conditional-orders";
        if (tool !== "create_conditional_order") {
          // claim 뒤 원주문의 공식 필드가 하나라도 바뀌었으면 보내지 않는다. 상태만으로 거절하지 않는다.
          const current = await readExpectedConditionalOrder(
            client,
            input.conditionalOrderId,
          );
          if (!isDeepStrictEqual(current, input.expected_conditional_order))
            reject();
          path =
            conditionalPath(input.conditionalOrderId) +
            (cancel ? "" : "/modify");
        }
        if (!cancel && input.type === "OCO")
          checkOcoPrice(
            input,
            await readLastPrice(
              client,
              input.symbol ?? input.expected_conditional_order.symbol,
            ),
          );
        approval.beforeSend();
        const response = await client.financial(
          cancel ? "DELETE" : "POST",
          path,
          cancel ? undefined : body,
          approval,
        );
        // 취소는 빈 204 만 성공이다. 공식 JSON 응답이 있었다고 표시하지 않는다.
        if (cancel)
          return {
            conditionalOrderId: input.conditionalOrderId,
            canceled: true,
          };
        const id = response.result.conditionalOrderId;
        if (
          tool === "modify_conditional_order" &&
          id === input.conditionalOrderId
        )
          throw new TossinvestError(UNKNOWN);
        const returned = response.result.clientOrderId;
        if (
          tool === "create_conditional_order" &&
          returned !== undefined &&
          returned !== null &&
          returned !== input.clientOrderId
        )
          throw new TossinvestError(UNKNOWN);
        return {
          conditionalOrderId: id,
          ...(tool === "create_conditional_order" && returned !== undefined
            ? { clientOrderId: returned }
            : {}),
        };
      },
    );
  }
}
