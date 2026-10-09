"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  fetchWorkspaceUsage,
  type WorkspaceSpaceUsage,
  type WorkspaceUsageReport,
} from "@/lib/workspace-api";
import { formatSize } from "@/lib/workspace-file";

/** 용량을 읽는다. 읽지 못하면 `null` 이다. */
async function readUsage(): Promise<WorkspaceUsageReport | null> {
  try {
    const response = await fetchWorkspaceUsage();
    return response.ok
      ? ((await response.json()) as WorkspaceUsageReport)
      : null;
  } catch {
    return null;
  }
}

/** 공간의 주인을 적는다. 이름을 찾지 못한 공간은 「알 수 없음」 이다. */
function ownerOf(space: WorkspaceSpaceUsage): string {
  const name = space.name || "알 수 없음";
  return space.kind === "AGENT" ? `에이전트 · ${name}` : name;
}

/**
 * 관리자 영역의 실행 공간별 용량이다. 파일 이름은 받지도 그리지도 않는다.
 *
 * <p>실패는 오류 코드 없이 「불러오지 못했어요」 와 다시 읽기만 그린다.
 */
export function AdminWorkspaceList() {
  const [report, setReport] = useState<WorkspaceUsageReport | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    void readUsage().then((next) => {
      if (cancelled) return;
      if (next === null) setFailed(true);
      else setReport(next);
    });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  function retry() {
    setFailed(false);
    setReport(null);
    setAttempt((value) => value + 1);
  }

  return (
    <div className="mx-auto w-full max-w-3xl">
      <h1 className="mb-4 text-xl font-semibold">파일 공간</h1>
      {failed ? (
        <Notice variant="error" role="alert">
          <p>불러오지 못했어요</p>
          <Button variant="outline" size="sm" className="mt-2" onClick={retry}>
            다시 읽기
          </Button>
        </Notice>
      ) : report === null ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : !report.available ? (
        <Notice variant="error" role="alert">
          파일 공간을 쓸 수 없어요
        </Notice>
      ) : report.spaces.length === 0 ? (
        <EmptyState
          title="아직 실행 공간이 없어요"
          description="에이전트가 실행되면 공간이 생기고 여기에 나타나요."
        />
      ) : (
        <div className="min-w-0 rounded-md border border-border">
          <Table aria-label="실행 공간별 용량">
            <TableHeader className="bg-muted">
              <TableRow className="hover:bg-transparent">
                <TableHead scope="col" className="px-3">
                  주인
                </TableHead>
                <TableHead scope="col" className="px-3 text-right">
                  용량
                </TableHead>
                <TableHead scope="col" className="px-3 text-right">
                  항목 수
                </TableHead>
                <TableHead scope="col" className="px-3">
                  <span className="sr-only">일부만 셌는지</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {report.spaces.map((space) => (
                <TableRow
                  key={`${space.kind}-${space.id}`}
                  data-testid="admin-workspace"
                >
                  <TableHead scope="row" className="px-3 text-foreground">
                    {ownerOf(space)}
                  </TableHead>
                  <TableCell className="px-3 text-right tabular-nums">
                    {formatSize(space.bytes)}
                  </TableCell>
                  <TableCell className="px-3 text-right tabular-nums">
                    {`${space.entries.toLocaleString("ko-KR")}개`}
                  </TableCell>
                  <TableCell className="px-3">
                    {space.partial ? (
                      <Badge variant="warning">일부만 셈</Badge>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
