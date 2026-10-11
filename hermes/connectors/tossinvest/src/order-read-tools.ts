import { z } from "zod";
import type { Commission, Order, OrderExecution } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { TossinvestError } from "./errors.ts";
import type { RegisterTool } from "./tool-registration.ts";

/** 공식 필드만 옮겨 계좌 응답의 확장키를 노출하지 않는다. null과 부재는 구별한다. */
function pick<T extends object>(value: T, keys: readonly (keyof T)[]): T {
  return Object.fromEntries(
    keys
      .filter((key) => Object.hasOwn(value, key))
      .map((key) => [key, value[key]]),
  ) as T;
}

function orderResult(order: Order): Order {
  const result = pick(order, [
    "orderId",
    "symbol",
    "side",
    "orderType",
    "timeInForce",
    "status",
    "price",
    "quantity",
    "orderAmount",
    "currency",
    "orderedAt",
    "canceledAt",
    "execution",
  ]);
  result.execution = pick<OrderExecution>(order.execution, [
    "filledQuantity",
    "averageFilledPrice",
    "filledAmount",
    "commission",
    "tax",
    "filledAt",
    "settlementDate",
  ]);
  return result;
}

/** opaque ID의 숫자 여부와 길이는 제한하지 않고 경로 주입만 막는다. */
function orderPath(orderId: string): string {
  if (
    !orderId ||
    orderId === "." ||
    orderId === ".." ||
    /[\/\\?#%\p{Cc}\p{Cs}]/u.test(orderId)
  )
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return `/api/v1/orders/${encodeURIComponent(orderId)}`;
}

export function registerOrderReadTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  register(
    "get_order",
    { orderId: z.string() },
    { readOnlyHint: true },
    async ({ orderId }) => {
      const envelope = await client.request(orderPath(orderId), {
        account: true,
      });
      return { result: orderResult(envelope.result), metadata: {} };
    },
  );

  register("get_commissions", {}, { readOnlyHint: true }, async () => {
    // 공식 API는 query 없이 모든 시장의 수수료를 반환한다. 비율은 문자열 그대로 둔다.
    const envelope = await client.request("/api/v1/commissions", {
      account: true,
    });
    return {
      result: envelope.result.map((row: Commission) =>
        pick(row, ["marketCountry", "commissionRate", "startDate", "endDate"]),
      ),
      metadata: {},
    };
  });
}
