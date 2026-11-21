"use client";

import { FinancialLimitsSection } from "@/components/connector/financial-limits";
import type { ConnectorTool } from "@/lib/connection";

/** 연결된 도구에 금융 도구가 있으면 주문 상한을 보인다. */
export function ConnectorLimitSections({ tools }: { tools: ConnectorTool[] }) {
  return tools.some((tool) => tool.risk === "FINANCIAL") ? (
    <FinancialLimitsSection />
  ) : null;
}
