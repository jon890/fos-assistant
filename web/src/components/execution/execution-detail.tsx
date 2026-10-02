"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { useShellIsAdmin } from "@/components/shell/app-shell";
import {
  agentLabel,
  formatCost,
  formatDuration,
  formatDurationFor,
  formatTokens,
  formatWhen,
} from "@/lib/format";
import { providerLabel } from "@/lib/provider-label";
import { executionStatusVariant } from "@/lib/execution-status";
import {
  ExecutionTree,
  type ExecutionTreeNode,
  type ExecutionTreeResponse,
} from "./execution-tree";

type FetchState =
  | { kind: "loading" }
  | { kind: "not-found" }
  | { kind: "error" }
  | { kind: "ready"; tree: ExecutionTreeResponse };

function findNode(
  node: ExecutionTreeNode,
  executionId: number,
): ExecutionTreeNode | null {
  if (node.executionId === executionId) return node;
  for (const child of node.children) {
    const found = findNode(child, executionId);
    if (found) return found;
  }
  return null;
}

function statusLabel(status: string): string {
  if (status === "RUNNING") return "실행 중";
  if (status === "SUCCEEDED") return "성공";
  if (status === "FAILED") return "실패";
  if (status === "CANCELLED") return "취소됨";
  return status;
}

function tierLabel(tier: ExecutionTreeNode["modelTier"]): string {
  if (tier === "FAST") return "빠르게";
  if (tier === "BALANCED") return "균형";
  if (tier === "DEEP") return "깊게";
  return "-";
}

function effortSourceLabel(node: ExecutionTreeNode): string {
  if (node.reasoningEffort === null || node.reasoningEffort === undefined)
    return "미확인";
  if (node.reasoningEffortSource === "PROFILE_DEFAULT")
    return `기본값 ${node.reasoningEffort}`;
  if (node.reasoningEffortSource === "AGENT_DEFAULT")
    return `에이전트 기본값 ${node.reasoningEffort}`;
  if (node.reasoningEffortSource === "UNKNOWN") return "미확인";
  return `선택한 값 ${node.reasoningEffort}`;
}

function recordedTimes(
  node: ExecutionTreeNode,
): Array<{ label: string; value: string }> {
  return [
    { label: "요청 수신", value: node.requestReceivedAt },
    { label: "Hermes 제출", value: node.submittedAt },
    { label: "첫 응답", value: node.firstDeltaAt },
    { label: "완료", value: node.finishedAt },
  ].filter(
    (item): item is { label: string; value: string } =>
      item.value !== null && item.value !== undefined,
  );
}

function interval(from: string, to: string): string | null {
  const milliseconds = new Date(to).getTime() - new Date(from).getTime();
  return Number.isFinite(milliseconds) && milliseconds >= 0
    ? formatDuration(milliseconds)
    : null;
}

/** 실행 하나의 머리 요약과 트리를 함께 읽고 그린다. 화면을 열 때 한 번만 읽는다. */
export function ExecutionDetail({ executionId }: { executionId: number }) {
  const router = useRouter();
  const isAdmin = useShellIsAdmin();
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
    return <p className="text-sm text-muted-foreground">불러오고 있어요.</p>;
  }

  if (state.kind === "error") {
    return (
      <div>
        <h1 className="mb-2 text-xl font-semibold">{`실행 #${executionId}`}</h1>
        <p className="text-sm text-destructive" role="alert">
          실행 정보를 불러오지 못했어요.
        </p>
      </div>
    );
  }

  const summary = findNode(state.tree.root, executionId) ?? state.tree.root;
  const running = summary.status === "RUNNING";
  const times = recordedTimes(summary);

  return (
    <div>
      <header className="mb-6">
        <h1 className="mb-4 text-xl font-semibold">
          {isAdmin
            ? (summary.agentName ??
              summary.agentCode ??
              `실행 #${summary.executionId}`)
            : agentLabel(summary.agentName)}
        </h1>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-muted-foreground">에이전트</dt>
            <dd>{summary.agentName ?? "-"}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">상태</dt>
            <dd>
              <Badge variant={executionStatusVariant(summary)}>
                {statusLabel(summary.status)}
              </Badge>
            </dd>
          </div>
          {isAdmin ? (
            <div>
              <dt className="text-muted-foreground">모델</dt>
              <dd className="truncate">
                {[providerLabel(summary.provider), summary.model]
                  .filter(Boolean)
                  .join(" · ") || "-"}
              </dd>
            </div>
          ) : null}
          <div>
            <dt className="text-muted-foreground">단계</dt>
            <dd>{tierLabel(summary.modelTier)}</dd>
          </div>
          {isAdmin ? (
            <>
              <div>
                <dt className="text-muted-foreground">리즈닝 강도</dt>
                <dd>{effortSourceLabel(summary)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">입력 토큰</dt>
                <dd className="tabular-nums">
                  {formatTokens(summary.inputTokens)}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">출력 토큰</dt>
                <dd className="tabular-nums">
                  {formatTokens(summary.outputTokens)}
                </dd>
              </div>
              {summary.cachedInputTokens === undefined ? null : (
                <div>
                  <dt className="text-muted-foreground">캐시 토큰</dt>
                  <dd className="tabular-nums">
                    {formatTokens(summary.cachedInputTokens ?? null)}
                  </dd>
                </div>
              )}
              <div>
                <dt className="text-muted-foreground">환산 금액</dt>
                <dd>{formatCost(summary.estimatedCostMicros, null)}</dd>
              </div>
            </>
          ) : null}
          <div>
            <dt className="text-muted-foreground">걸린 시간</dt>
            <dd>
              {running || summary.latencyMs === null
                ? ""
                : formatDurationFor(summary.latencyMs, isAdmin)}
            </dd>
          </div>
        </dl>
        {isAdmin && times.length > 0 ? (
          <dl
            className="mt-5 grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-4"
            data-testid="execution-timing"
          >
            {times.map((item, index) => (
              <div key={item.label}>
                <dt className="text-muted-foreground">{item.label}</dt>
                <dd className="tabular-nums">{formatWhen(item.value)}</dd>
                {index === 0
                  ? null
                  : (() => {
                      const elapsed = interval(
                        times[index - 1].value,
                        item.value,
                      );
                      return elapsed === null ? null : (
                        <span className="text-xs text-muted-foreground">
                          앞 단계 뒤 {elapsed}
                        </span>
                      );
                    })()}
              </div>
            ))}
          </dl>
        ) : null}
      </header>
      <ExecutionTree
        tree={state.tree}
        isAdmin={isAdmin}
        showRuntime={isAdmin}
      />
    </div>
  );
}
