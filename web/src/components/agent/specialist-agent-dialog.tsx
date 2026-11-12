"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Notice } from "@/components/ui/notice";
import { readConnectors, type ConnectorSummary } from "@/lib/connection";
import { SpecialistAgentForm } from "./specialist-agent-form";

type Connectors =
  | { kind: "loading" }
  | { kind: "failed"; message: string }
  | { kind: "ready"; connectors: ConnectorSummary[] };

/**
 * 에이전트 목록의 「전문 에이전트 만들기」 다(docs/features/agent-team/spec.md 의 REQ5).
 * 내 연결 가운데 준비된 것을 고르면 연결을 마친 화면과 같은 만들기 화면이 열린다. 만들면 그 에이전트 상세로 옮긴다.
 */
export function SpecialistAgentDialog() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [connectors, setConnectors] = useState<Connectors>({ kind: "loading" });
  const [picked, setPicked] = useState<ConnectorSummary | null>(null);
  // 생성 요청을 보낸 때부터 잠근다. 요청 중 닫고 다시 열어 한 번 더 만들거나, 닫은 창의 요청이 뒤늦게 화면을 옮기지 않게 한다.
  // 만든 뒤에는 상세로 옮길 때까지 잠근 채 둔다.
  const [creating, setCreating] = useState(false);

  async function load() {
    setConnectors({ kind: "loading" });
    const result = await readConnectors();
    setConnectors(
      result.ok
        ? {
            kind: "ready",
            connectors: result.data.filter(
              (connector) =>
                connector.available && connector.myStatus === "READY",
            ),
          }
        : { kind: "failed", message: result.message },
    );
  }

  function changeOpen(next: boolean) {
    if (creating) return;
    setOpen(next);
    setPicked(null);
    if (next) void load();
  }

  return (
    <>
      <Button variant="outline" onClick={() => changeOpen(true)}>
        전문 에이전트 만들기
      </Button>
      <Dialog open={open} onOpenChange={changeOpen}>
        <DialogContent showCloseButton={!creating}>
          <DialogHeader>
            <DialogTitle>전문 에이전트 만들기</DialogTitle>
            <DialogDescription>
              연결한 서비스 하나만 맡는 에이전트를 만들어요.
            </DialogDescription>
          </DialogHeader>
          {picked ? (
            <SpecialistAgentForm
              connectorId={picked.id}
              title={picked.title}
              bindings={picked.bindings}
              singleBinding={picked.singleBinding === true}
              cancelLabel="다른 서비스 고르기"
              onCancel={() => setPicked(null)}
              onCreatingChange={setCreating}
              onCreated={(result) => {
                router.push(`/agents/${result.agentCode}`);
              }}
            />
          ) : (
            <ConnectorPicker connectors={connectors} onPick={setPicked} />
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

function ConnectorPicker({
  connectors,
  onPick,
}: {
  connectors: Connectors;
  onPick(connector: ConnectorSummary): void;
}) {
  if (connectors.kind === "loading") {
    return <p className="text-sm text-muted-foreground">서비스를 읽는 중…</p>;
  }
  if (connectors.kind === "failed") {
    return (
      <Notice variant="error" role="alert">
        {connectors.message}
      </Notice>
    );
  }
  if (connectors.connectors.length === 0) {
    return (
      <div className="grid gap-3 text-sm">
        <p>연결을 마친 서비스가 없어요. 먼저 서비스를 연결해 주세요.</p>
        <div>
          <Button asChild>
            <Link href="/connections">외부 서비스 연결</Link>
          </Button>
        </div>
      </div>
    );
  }
  return (
    <ul
      aria-label="연결한 서비스"
      className="divide-y divide-border rounded-md border border-border"
    >
      {connectors.connectors.map((connector) => (
        <li
          key={connector.id}
          className="flex flex-wrap items-center justify-between gap-3 p-3"
        >
          <div className="min-w-0 text-sm">
            <p className="font-medium break-all">{connector.title}</p>
            {connector.bindings.length > 0 ? (
              <p className="mt-1 text-xs text-muted-foreground">
                붙은 에이전트:{" "}
                {connector.bindings
                  .map((binding) => binding.agentName)
                  .join(", ")}
              </p>
            ) : null}
          </div>
          <Button size="sm" onClick={() => onPick(connector)}>
            이 서비스로 만들기
          </Button>
        </li>
      ))}
    </ul>
  );
}
