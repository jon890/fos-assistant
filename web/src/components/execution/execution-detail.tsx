"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { formatCost, formatDuration, formatTokens } from "@/lib/format";
import { ExecutionTree, type ExecutionTreeNode, type ExecutionTreeResponse } from "./execution-tree";

type FetchState =
  | { kind: "loading" }
  | { kind: "not-found" }
  | { kind: "error" }
  | { kind: "ready"; tree: ExecutionTreeResponse };

function findNode(node: ExecutionTreeNode, executionId: number): ExecutionTreeNode | null {
  if (node.executionId === executionId) return node;
  for (const child of node.children) {
    const found = findNode(child, executionId);
    if (found) return found;
  }
  return null;
}

function statusLabel(status: string): string {
  if (status === "RUNNING") return "도는 중";
  if (status === "SUCCEEDED") return "성공";
  if (status === "FAILED") return "실패";
  if (status === "CANCELLED") return "취소됨";
  return status;
}

/** 실행 하나의 머리 요약과 나무를 함께 읽고 그린다. 화면을 열 때 한 번만 읽는다. */
export function ExecutionDetail({ executionId }: { executionId: number }) {
  const router = useRouter();
  const [state, setState] = useState<FetchState>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    fetch(`/api/usage/executions/${executionId}/tree`, { cache: "no-store" })
      .then(async (response) => {
        if (cancelled) return;
        if (response.status === 404) {
          setState({ kind: "not-found" });
          return;
        }
        if (!response.ok) {
          setState({ kind: "error" });
          return;
        }
        const tree = (await response.json()) as ExecutionTreeResponse;
        setState({ kind: "ready", tree });
      })
      .catch(() => {
        if (!cancelled) setState({ kind: "error" });
      });
    return () => {
      cancelled = true;
    };
  }, [executionId]);

  useEffect(() => {
    if (state.kind === "not-found") router.replace("/usage");
  }, [state.kind, router]);

  if (state.kind === "loading" || state.kind === "not-found") {
    return <p className="text-sm text-muted">불러오는 중이다.</p>;
  }

  if (state.kind === "error") {
    return (
      <div>
        <h1 className="mb-2 text-xl font-semibold">{`실행 #${executionId}`}</h1>
        <p className="text-sm text-muted" role="alert">
          실행 정보를 불러오지 못했다.
        </p>
      </div>
    );
  }

  const summary = findNode(state.tree.root, executionId) ?? state.tree.root;
  const running = summary.status === "RUNNING";

  return (
    <div>
      <header className="mb-6">
        <h1 className="mb-4 text-xl font-semibold">
          {summary.agentName ?? summary.agentCode ?? `실행 #${summary.executionId}`}
        </h1>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-muted">에이전트</dt>
            <dd>{summary.agentName ?? "-"}</dd>
          </div>
          <div>
            <dt className="text-muted">상태</dt>
            <dd>{statusLabel(summary.status)}</dd>
          </div>
          <div>
            <dt className="text-muted">모델</dt>
            <dd className="truncate">{summary.model ?? "-"}</dd>
          </div>
          <div>
            <dt className="text-muted">입력 토큰</dt>
            <dd className="tabular-nums">{formatTokens(summary.inputTokens)}</dd>
          </div>
          <div>
            <dt className="text-muted">출력 토큰</dt>
            <dd className="tabular-nums">{formatTokens(summary.outputTokens)}</dd>
          </div>
          <div>
            <dt className="text-muted">환산 금액</dt>
            <dd>{formatCost(summary.estimatedCostMicros, null)}</dd>
          </div>
          <div>
            <dt className="text-muted">걸린 시간</dt>
            <dd>{running || summary.latencyMs === null ? "" : formatDuration(summary.latencyMs)}</dd>
          </div>
        </dl>
      </header>
      <ExecutionTree tree={state.tree} />
    </div>
  );
}
