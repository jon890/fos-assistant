"use client";

import { useState } from "react";
import { NativeSelect } from "@/components/ui/native-select";
import { fetchUsageBreakdown } from "@/lib/usage-api";
import { BreakdownTable, type Breakdown } from "./breakdown-table";

/** 화면이 고를 수 있는 축이다. Control Plane 이 받는 값과 같아야 한다. */
const AXES = [
  { value: "agent", label: "에이전트" },
  { value: "model", label: "모델" },
  { value: "day", label: "날짜" },
  { value: "fingerprint", label: "설정별 사용량" },
] as const;

export function BreakdownSection({ initial }: { initial: Breakdown }) {
  const [breakdown, setBreakdown] = useState(initial);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function changeAxis(axis: string) {
    setPending(true);
    setFailure(null);
    try {
      const response = await fetchUsageBreakdown(axis, breakdown.month);
      if (!response.ok) {
        setFailure("묶음별 합계를 불러오지 못했어요.");
        return;
      }
      setBreakdown((await response.json()) as Breakdown);
    } catch {
      setFailure("묶음별 합계를 불러오지 못했어요.");
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-label="사용량 내역" className="mb-8">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">사용량 내역</h2>
        <label className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">묶는 기준</span>
          <NativeSelect
            wrapperClassName="w-auto"
            data-testid="breakdown-axis"
            disabled={pending}
            onChange={(event) => changeAxis(event.target.value)}
            value={breakdown.axis}
          >
            {AXES.map((axis) => (
              <option key={axis.value} value={axis.value}>
                {axis.label}
              </option>
            ))}
          </NativeSelect>
        </label>
      </div>
      {failure ? <p className="mb-3 text-sm">{failure}</p> : null}
      <BreakdownTable
        axis={breakdown.axis}
        currency={breakdown.currency}
        rows={breakdown.rows}
      />
    </section>
  );
}
