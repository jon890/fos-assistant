import { expect } from "bun:test";
import canonical from "./fixtures/openapi-1.2.24.json";

export const sensitiveMarker = "sensitive-extension-synthetic-only";
export const sensitiveExtension = {
  accountNo: sensitiveMarker,
  token: sensitiveMarker,
  extension: { rows: [{ access_token: sensitiveMarker }] },
};

/** 기대 필드 목록은 투영 구현에서 가져오지 않고 공식 계약과 별도로 대조한다. */
export function boundaryCases(): any[] {
  const schemas = canonical.components.schemas;
  expect(
    Object.keys(schemas.ConditionalOrderDetailResponse.properties).sort(),
  ).toEqual(
    [
      "conditionalOrderId",
      "type",
      "status",
      "symbol",
      "market",
      "quantity",
      "orderType",
      "expireDate",
      "first",
      "second",
      "createdAt",
    ].sort(),
  );
  expect(
    Object.keys(schemas.ConditionalOrderCondition.properties).sort(),
  ).toEqual(
    [
      "type",
      "status",
      "orderSide",
      "triggerPrice",
      "targetProfitRate",
      "orderPrice",
      "triggeredOrderId",
    ].sort(),
  );
  const official =
    canonical.paths["/api/v1/conditional-orders/{conditionalOrderId}"].get
      .responses["200"].content["application/json"].example.result;
  return Array.from({ length: 4 }, (_, index) => {
    const row: any = {
      ...structuredClone(official),
      conditionalOrderId: "opaque-9223372036854775808-가😀",
      type: "NEXT_TYPE",
      status: "NEXT_STATUS",
      market: "NEXT_MARKET",
      orderType: "NEXT_ORDER",
      quantity: "9223372036854775808.000000000000000000001",
      first: {
        type: "NEXT_LEG",
        status: "NEXT_LEG_STATUS",
        orderSide: "NEXT_SIDE",
        triggerPrice: "0.0000000000000000000001",
        targetProfitRate: "-0.7777777777777777777777",
        orderPrice: null,
        triggeredOrderId: "9223372036854775808",
      },
      second: {
        type: "PROFIT_RATE",
        status: "HOLDING",
        orderSide: "BUY",
        triggerPrice: null,
        targetProfitRate: "10.5",
        orderPrice: "0.000000000000000000001",
        triggeredOrderId: null,
      },
    };
    if (index === 1) row.second = null;
    if (index === 2) {
      delete row.expireDate;
      delete row.second;
      for (const key of [
        "triggerPrice",
        "targetProfitRate",
        "orderPrice",
        "triggeredOrderId",
      ])
        delete row.first[key];
    }
    if (index === 3) row.second.status = "CANCELED";
    return row;
  });
}

export function withExtensions(row: any): any {
  return {
    ...structuredClone(row),
    ...sensitiveExtension,
    first: { ...row.first, ...sensitiveExtension },
    ...(row.second ? { second: { ...row.second, ...sensitiveExtension } } : {}),
  };
}
