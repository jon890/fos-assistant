import { z } from "zod";
import { SCHEMAS } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { TossinvestError } from "./errors.ts";
import type { RegisterTool } from "./tool-registration.ts";
import { officialFields } from "./official-response.ts";

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
      return {
        result: officialFields(envelope.result, SCHEMAS.Order),
        metadata: {},
      };
    },
  );

  register("get_commissions", {}, { readOnlyHint: true }, async () => {
    // 공식 API는 query 없이 모든 시장의 수수료를 반환한다. 비율은 문자열 그대로 둔다.
    const envelope = await client.request("/api/v1/commissions", {
      account: true,
    });
    return {
      result: officialFields(envelope.result, {
        type: "array",
        items: SCHEMAS.Commission,
      }),
      metadata: {},
    };
  });
}
