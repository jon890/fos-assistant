"use client";

import { useEffect, useState } from "react";
import { readRequestResponse, fetchToolsetRequests, requestToolset, cancelToolsetRequest,
  type ToolsetRequest } from "@/lib/toolset-request";

/** 도구 절 전체에서 요청 목록을 한 번 읽고, 변경 응답으로 상태를 갱신한다. */
export function useToolsetRequests(code: string, admin: boolean) {
  const [rows, setRows] = useState<ToolsetRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void fetchToolsetRequests(code, admin).then(readRequestResponse<ToolsetRequest[]>).then((items) => {
      if (active) setRows(items);
    }).catch(() => {
      if (active) setError("사용 요청을 불러오지 못했어요. 화면을 다시 열어 주세요.");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [code, admin]);

  async function change(toolset: string, pending?: ToolsetRequest) {
    setBusy(toolset);
    setError(null);
    try {
      const response = await (pending ? cancelToolsetRequest(pending.id) : requestToolset(code, toolset));
      const row = await readRequestResponse<ToolsetRequest>(response);
      setRows((current) => [row, ...current.filter((item) => item.id !== row.id)]);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "사용 요청을 보내지 못했어요.");
    } finally { setBusy(null); }
  }

  return { rows, loading, busy, error, change };
}
