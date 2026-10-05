"use client";

import Link from "next/link";
import { useState } from "react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { GROUP_VISIBILITY, type AdminAgent } from "@/lib/agent";
import {
  agentConnectionLabel,
  bindAgentConnection,
  hasShellOrFileTool,
  unbindAgentConnection,
  type AgentConnectionBlock,
  type AgentConnectionView,
} from "@/lib/agent-connection";

type Props = {
  code: string;
  initialConnections: AgentConnectionView[];
  initialBlockedReason: AgentConnectionBlock | null;
  visibility: AdminAgent["visibility"] | undefined;
  /** 지금 켜진 도구 이름이다. 도구를 읽지 못했으면 null 이고 도구에 따른 안내를 하지 않는다. */
  enabledTools: string[] | null;
  /** 붙거나 떨어진 뒤 이 에이전트에 붙은 연결이 있는지 알린다. 도구 절의 위험 안내가 받는다. */
  onBoundChange(bound: boolean): void;
};

const BLOCKED_MESSAGES: Record<AgentConnectionBlock, string> = {
  AGENT_NOT_PRIVATE: "비공개 에이전트에만 붙일 수 있어요.",
  // 서버가 돌려줄 수 있어 타입과 문구를 채워 둔다. 지금 화면은 옛 에이전트에서 이 절을 그리지 않는다.
  LEGACY_AGENT:
    "예전 방식의 연결 에이전트에는 붙일 수 없어요. 쓰던 에이전트에 붙여 주세요.",
};

const RISK_MESSAGE =
  "이 에이전트가 이 연결의 도구를 직접 써요. 터미널이나 파일 도구가 켜진 에이전트는 연결의 비밀값에 닿을 수 있어요.";

const SHELL_RISK_MESSAGE =
  "이 에이전트는 연결 도구의 승인 없이 그 서비스를 부를 수 있어요.";

/** 붙이기 확인 창이다. 요청이 도는 동안 닫히지 않고, 실패하면 창이 남아 까닭을 보인다. */
function BindConfirm({
  title,
  busy,
  error,
  shellOrFile,
  onCancel,
  onConfirm,
}: {
  title: string;
  busy: boolean;
  error: string | null;
  shellOrFile: boolean;
  onCancel(): void;
  onConfirm(): void;
}) {
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open) onCancel();
      }}
    >
      <AlertDialogContent
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{title} 연결을 붙일까요?</AlertDialogTitle>
          <AlertDialogDescription>{RISK_MESSAGE}</AlertDialogDescription>
        </AlertDialogHeader>
        {shellOrFile ? (
          <Notice variant="warning">{SHELL_RISK_MESSAGE}</Notice>
        ) : null}
        {error ? (
          <Notice variant="error" role="alert">
            {error}
          </Notice>
        ) : null}
        <AlertDialogFooter>
          {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>
              취소
            </Button>
          </AlertDialogCancel>
          {/* 붙이기가 실패해도 창이 남아 까닭을 보이게 일반 Button 으로 둔다. */}
          <Button loading={busy} loadingText="붙이는 중" onClick={onConfirm}>
            붙이기
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function stateVariant(view: AgentConnectionView) {
  if (!view.bound) return "outline" as const;
  return agentConnectionLabel(view) === "붙음"
    ? ("success" as const)
    : ("warning" as const);
}

/** 연결 한 줄이다. 붙일 수 없는 에이전트와 준비되지 않은 연결에는 붙이기 단추가 없다. */
function ConnectionRow({
  view,
  pending,
  blocked,
  skillsOff,
  onBind,
  onUnbind,
}: {
  view: AgentConnectionView;
  pending: string | null;
  blocked: boolean;
  skillsOff: boolean;
  onBind(): void;
  onUnbind(): void;
}) {
  const ready = view.connectionStatus === "READY";
  return (
    <li
      data-testid="agent-connection"
      className="flex flex-wrap items-center justify-between gap-3 p-3"
    >
      <div className="min-w-0 text-sm">
        <p className="font-medium break-all">{view.title}</p>
        <p className="mt-1 text-xs text-muted-foreground">
          도구 {view.toolCount}개
        </p>
        {!view.bound && !ready && !blocked ? (
          <p className="mt-1 text-xs text-muted-foreground">
            연결 화면에서 연결을 확인해 주세요.
          </p>
        ) : null}
        {view.skills.length > 0 && skillsOff ? (
          <p className="mt-1 text-xs text-muted-foreground">
            지침을 쓰려면 스킬 도구를 켜세요.
          </p>
        ) : null}
      </div>
      <div className="flex items-center gap-2">
        <Badge
          variant={stateVariant(view)}
          data-testid="agent-connection-state"
        >
          {agentConnectionLabel(view)}
        </Badge>
        {view.bound ? (
          <Button
            size="sm"
            variant="outline"
            disabled={pending !== null}
            loading={pending === view.connectorId}
            loadingText="떼는 중"
            onClick={onUnbind}
          >
            떼기
          </Button>
        ) : !blocked && ready ? (
          <Button
            size="sm"
            variant="outline"
            disabled={pending !== null}
            onClick={onBind}
          >
            붙이기
          </Button>
        ) : null}
      </div>
    </li>
  );
}

/**
 * 「이 에이전트가 쓰는 연결」 이다. 주인이 자기 연결을 이 에이전트에 붙이고 뗀다.
 *
 * <p>붙이기는 확인 창을 거친다. 붙인 연결의 도구를 이 에이전트가 직접 쓰므로, 셸과 파일 도구가 켜져 있으면 그 도구로 비밀값을
 * 읽고 승인 없이 그 서비스를 부를 수 있다는 것까지 알린다. 계정 연결은 연결 화면이 한다.
 */
export function AgentConnectionsSection({
  code,
  initialConnections,
  initialBlockedReason,
  visibility,
  enabledTools,
  onBoundChange,
}: Props) {
  const [connections, setConnections] = useState(initialConnections);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<AgentConnectionView | null>(
    null,
  );
  // 공개 범위는 이 화면에서 바뀔 수 있다. 그룹 공개를 비공개로 되돌리면 다시 붙일 수 있다.
  // 공개 범위를 모르면 서버가 준 까닭을 그대로 쓴다.
  const blockedReason: AgentConnectionBlock | null =
    visibility === undefined || initialBlockedReason === "LEGACY_AGENT"
      ? initialBlockedReason
      : visibility === GROUP_VISIBILITY
        ? "AGENT_NOT_PRIVATE"
        : null;
  const shellOrFile = enabledTools !== null && hasShellOrFileTool(enabledTools);
  const skillsOff = enabledTools !== null && !enabledTools.includes("skills");

  function replace(next: AgentConnectionView) {
    const updated = connections.map((item) =>
      item.connectorId === next.connectorId ? next : item,
    );
    setConnections(updated);
    onBoundChange(updated.some((item) => item.bound));
  }

  async function bind(view: AgentConnectionView) {
    setPending(view.connectorId);
    setError(null);
    const result = await bindAgentConnection(code, view.connectorId);
    setPending(null);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    setConfirming(null);
    replace(result.data);
  }

  async function unbind(view: AgentConnectionView) {
    if (pending !== null) return;
    setPending(view.connectorId);
    setError(null);
    const result = await unbindAgentConnection(code, view.connectorId);
    setPending(null);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    replace({ ...view, bound: false, status: null, restartRequired: false });
  }

  function cancel() {
    if (pending !== null) return;
    setConfirming(null);
    setError(null);
  }

  return (
    <section
      aria-label="이 에이전트가 쓰는 연결"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">이 에이전트가 쓰는 연결</h2>
      {connections.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">
          아직 연결한 계정이 없어요.{" "}
          <Link
            href="/connections"
            className="text-foreground underline underline-offset-4"
          >
            연결 화면
          </Link>
          에서 먼저 연결해요.
        </p>
      ) : (
        <>
          <p className="mt-1 text-sm text-muted-foreground">
            붙인 연결의 도구를 이 에이전트가 직접 써요. 계정은{" "}
            <Link
              href="/connections"
              className="text-foreground underline underline-offset-4"
            >
              연결 화면
            </Link>
            에서 연결해요.
          </p>
          {blockedReason ? (
            <Notice variant="info" role="status" className="mt-4">
              {BLOCKED_MESSAGES[blockedReason]}
            </Notice>
          ) : null}
          {error && confirming === null ? (
            <Notice variant="error" role="alert" className="mt-4">
              {error}
            </Notice>
          ) : null}
          <ul className="mt-4 divide-y divide-border rounded-md border border-border">
            {connections.map((view) => (
              <ConnectionRow
                key={view.connectorId}
                view={view}
                pending={pending}
                blocked={blockedReason !== null}
                skillsOff={skillsOff}
                onUnbind={() => void unbind(view)}
                onBind={() => {
                  setError(null);
                  setConfirming(view);
                }}
              />
            ))}
          </ul>
        </>
      )}
      {confirming ? (
        <BindConfirm
          title={confirming.title}
          busy={pending !== null}
          error={error}
          shellOrFile={shellOrFile}
          onCancel={cancel}
          onConfirm={() => void bind(confirming)}
        />
      ) : null}
    </section>
  );
}
