import { expect, test } from "bun:test";
import { SCHEMAS } from "../src/api-contract.ts";
import { officialFields } from "../src/official-response.ts";
import canonical from "./fixtures/openapi-1.2.24.json";
import {
  boundaryCases,
  withExtensions,
} from "./conditional-boundary-fixture.ts";

test("공용 투영은 공식 조건조회 $ref·allOf·배열·nullable와 선택 필드 부재를 보존한다", () => {
  for (const row of boundaryCases()) {
    expect(
      officialFields(withExtensions(row), {
        $ref: "#/components/schemas/ConditionalOrderDetailResponse",
      }),
    ).toEqual(row);
    const page = {
      conditionalOrders: [row, { ...row, conditionalOrderId: "next" }],
      nextCursor: null,
      hasNext: false,
    };
    expect(
      officialFields(
        { ...page, token: "synthetic-sensitive" },
        SCHEMAS.PaginatedConditionalOrderResponse,
      ),
    ).toEqual(page);
  }
});

test("공용 투영은 공식 1.2.24 랭킹 응답 예시의 모든 알려진 값을 보존한다", () => {
  let count = 0;
  for (const [path, operations] of Object.entries(canonical.paths)) {
    if (!path.includes("ranking")) continue;
    const response = (operations as any).get?.responses?.["200"]?.content?.[
      "application/json"
    ];
    for (const example of Object.values(response.examples) as any[]) {
      expect(
        officialFields(
          example.value.result,
          path.includes("sectors")
            ? SCHEMAS.SectorRankingResponse
            : SCHEMAS.RankingResponse,
        ),
      ).toEqual(example.value.result);
      count++;
    }
  }
  expect(count).toBe(7);
});

test.each([
  {},
  { type: "object" },
  { type: "object", additionalProperties: true },
  { type: "object", additionalProperties: { type: "string" } },
  { oneOf: [{ type: "object" }] },
  { anyOf: [{ type: "object" }] },
  { $ref: "#/components/schemas/NotInOfficialContract" },
  { type: "array" },
  { properties: { nested: {} } },
])("미지원 객체 구조 %j는 민감 확장 원문 대신 전체 오류로 막는다", (schema) => {
  const value =
    schema.type === "array"
      ? [{ token: "synthetic-sensitive" }]
      : {
          token: "synthetic-sensitive",
          nested: { token: "synthetic-sensitive" },
        };
  expect(() => officialFields(value, schema)).toThrow(
    "TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA",
  );
});
