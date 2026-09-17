"use client";

import { useState } from "react";
import { BreakdownTable, type Breakdown } from "./breakdown-table";

/** 화면이 고를 수 있는 축이다. Control Plane 이 받는 값과 같아야 한다. */
const AXES = [
  { value: "agent", label: "에이전트" },
  { value: "model", label: "모델" },
  { value: "day", label: "날짜" },
  { value: "fingerprint", label: "설정 지문" },
] as const;

export function BreakdownSection({ initial }: { initial: Breakdown }) {
  const [breakdown, setBreakdown] = useState(initial);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function changeAxis(axis: string) {
    setPending(true);
    setFailure(null);
    try {
      const response = await fetch(
        `/api/usage/breakdown?axis=${encodeURIComponent(axis)}&month=${encodeURIComponent(breakdown.month)}`,
        { cache: "no-store" },
      );
      if (!response.ok) {
        setFailure("축별 합계를 불러오지 못했다.");
        return;
      }
      setBreakdown(await response.json() as Breakdown);
    } catch {
      setFailure("축별 합계를 불러오지 못했다.");
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-label="어디에 썼나" className="mb-8">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">어디에 썼나</h2>
        <label className="flex items-center gap-2 text-sm">
          <span className="text-muted">묶는 기준</span>
          <select
            className="rounded-md border border-border bg-surface px-control-x-sm py-control-y-sm text-sm"
            data-testid="breakdown-axis"
            disabled={pending}
            onChange={(event) => changeAxis(event.target.value)}
            value={breakdown.axis}
          >
            {AXES.map((axis) => <option key={axis.value} value={axis.value}>{axis.label}</option>)}
          </select>
        </label>
      </div>
      {failure ? <p className="mb-3 text-sm">{failure}</p> : null}
      <BreakdownTable axis={breakdown.axis} currency={breakdown.currency} rows={breakdown.rows} />
    </section>
  );
}
