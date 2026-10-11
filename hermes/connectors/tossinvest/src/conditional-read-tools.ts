import { z } from "zod";
import {
  envelopeMetadata,
  SCHEMAS,
  type ConditionalOrderDetailResponse,
  type PaginatedConditionalOrderResponse,
} from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { TossinvestError } from "./errors.ts";
import type { RegisterTool } from "./tool-registration.ts";

const invalidInput = (): never => {
  throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
};

/** 검증된 조건조회 응답에서 공식 필드만 남긴다. nullable·부재·문자열 값은 바꾸지 않는다. */
function officialFields(value: any, schema: any): any {
  if (value === null) return null;
  if (schema.$ref)
    return officialFields(value, SCHEMAS[schema.$ref.split("/").pop()]);
  if (schema.allOf)
    return schema.allOf.reduce(
      (result: any, branch: any) => ({
        ...result,
        ...officialFields(value, branch),
      }),
      {},
    );
  if (Array.isArray(value))
    return value.map((entry) => officialFields(entry, schema.items));
  if (schema.properties)
    return Object.fromEntries(
      Object.entries(schema.properties)
        .filter(([key]) => Object.hasOwn(value, key))
        .map(([key, child]) => [key, officialFields(value[key], child)]),
    );
  return value;
}

/** 불투명 ID를 한 segment로만 보낸다. URL이 정규화하는 점 경로도 거절한다. */
function orderSegment(id: string): string {
  if (
    !id ||
    id === "." ||
    id === ".." ||
    /[\s/\\?#%:\u0000-\u001f\u007f]/u.test(id)
  )
    return invalidInput();
  try {
    return encodeURIComponent(id);
  } catch {
    return invalidInput();
  }
}

export function registerConditionalReadTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  register(
    "list_conditional_orders",
    {
      status: z.string().default(""),
      symbol: z.string().optional(),
      cursor: z.string().optional(),
      limit: z.number().optional(),
    },
    { readOnlyHint: true },
    async ({ status, symbol, cursor, limit }) => {
      if (
        !["OPEN", "CLOSED"].includes(status) ||
        (symbol !== undefined && !/^[A-Za-z0-9.\-]+$/.test(symbol)) ||
        (cursor !== undefined && !/^[A-Za-z0-9_\-]+$/.test(cursor)) ||
        (limit !== undefined &&
          (!Number.isInteger(limit) || limit < 1 || limit > 100))
      )
        return invalidInput();
      const query = new URLSearchParams({ status, limit: String(limit ?? 20) });
      if (symbol !== undefined) query.set("symbol", symbol);
      if (cursor !== undefined) query.set("cursor", cursor);
      const envelope = await client.request("/api/v1/conditional-orders", {
        account: true,
        query,
      });
      const data = officialFields(
        envelope.result,
        SCHEMAS.PaginatedConditionalOrderResponse,
      ) as PaginatedConditionalOrderResponse;
      return {
        result: data,
        metadata: envelopeMetadata(envelope),
        conditionalOrders: data.conditionalOrders,
        nextCursor: data.nextCursor,
        hasNext: data.hasNext,
      };
    },
  );

  register(
    "get_conditional_order",
    { conditionalOrderId: z.string().default("") },
    { readOnlyHint: true },
    async ({ conditionalOrderId }) => {
      const segment = orderSegment(conditionalOrderId);
      const envelope = await client.request(
        `/api/v1/conditional-orders/${segment}`,
        { account: true },
      );
      return {
        result: officialFields(
          envelope.result,
          SCHEMAS.ConditionalOrderDetailResponse,
        ) as ConditionalOrderDetailResponse,
        metadata: envelopeMetadata(envelope),
      };
    },
  );
}
