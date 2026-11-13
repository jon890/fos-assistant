"use client";

import { FinancialLimitsSection } from "@/components/connector/financial-limits";
import { PurchaseLimitsSection } from "@/components/connector/purchase-limits";
import type { ConnectorTool } from "@/lib/connection";

/**
 * 연결 화면의 상한 절이다. 금융 도구가 있으면 주문 상한을, 구매 도구가 있으면 구매 상한을 보인다.
 * 두 상한은 서로 다른 칸이고 서로의 합계에 섞이지 않는다(ADR-20261113 / purchase-checkout).
 */
export function ConnectorLimitSections({ tools }: { tools: ConnectorTool[] }) {
  return (
    <>
      {tools.some((tool) => tool.risk === "FINANCIAL") ? (
        <FinancialLimitsSection />
      ) : null}
      {tools.some((tool) => tool.risk === "PURCHASE") ? (
        <PurchaseLimitsSection />
      ) : null}
    </>
  );
}
