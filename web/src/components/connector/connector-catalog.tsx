"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import {
  ConnectorIcon,
  ConnectorLink,
} from "@/components/connector/connector-identity";
import {
  connectionStatusLabel,
  readConnectors,
  toolRiskCounts,
  type ConnectorSummary,
} from "@/lib/connection";

/** 「도구 3개 · 조회 1 · 쓰기 2」 처럼 도구 수와 위험도별 수를 한 줄로 만든다. */
function toolSummary(tools: ConnectorSummary["tools"]): string {
  const parts = toolRiskCounts(tools).map(
    ({ label, count }) => `${label} ${count}`,
  );
  return [`도구 ${tools.length}개`, ...parts].join(" · ");
}

export function ConnectorCatalog() {
  const [connectors, setConnectors] = useState<ConnectorSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void readConnectors().then((result) => {
      if (result.ok) setConnectors(result.data);
      else setError(result.message);
    });
  }, []);

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="text-xl font-semibold">연결</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">
        계정을 한 번 연결하고, 에이전트 화면에서 그 에이전트가 쓸 연결을 붙여요.
      </p>
      {error ? (
        <Notice variant="error" role="alert">
          연결 목록을 읽지 못했어요. {error}
        </Notice>
      ) : connectors === null ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : connectors.length === 0 ? (
        <EmptyState
          title="연결할 수 있는 서비스가 없어요"
          description="관리자가 서비스를 열면 여기에 나타나요."
        />
      ) : (
        <ul className="space-y-3">
          {connectors.map((connector) => (
            <li key={connector.id}>
              <Card
                data-testid="connector-card"
                className="relative transition-colors hover:bg-accent has-[[data-slot=card-link]:focus-visible]:ring-3 has-[[data-slot=card-link]:focus-visible]:ring-ring/50"
              >
                <CardHeader>
                  <div className="flex min-w-0 items-start gap-3">
                    <ConnectorIcon icon={connector.icon} />
                    <div className="min-w-0 flex-1 space-y-1">
                      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
                        <CardTitle className="min-w-0 break-words">
                          <Link
                            data-slot="card-link"
                            prefetch={false}
                            href={`/connections/${connector.id}`}
                            className="after:absolute after:inset-0 focus-visible:outline-none"
                          >
                            {connector.title}
                          </Link>
                        </CardTitle>
                        <Badge variant="outline">
                          {connectionStatusLabel(connector.myStatus)}
                        </Badge>
                      </div>
                      {connector.description ? (
                        <CardDescription className="break-words">
                          {connector.description}
                        </CardDescription>
                      ) : null}
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="min-w-0 space-y-1">
                  {connector.available ? (
                    connector.tools.length > 0 ? (
                      <p
                        className="text-sm text-muted-foreground"
                        data-testid="connector-tool-summary"
                      >
                        {toolSummary(connector.tools)}
                      </p>
                    ) : null
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      지금은 쓸 수 없어요.
                    </p>
                  )}
                  {connector.myStatus === "DISCONNECTED" ? null : (
                    <p
                      className="text-sm text-muted-foreground"
                      data-testid="connector-binding-count"
                    >
                      붙인 에이전트 {connector.bindings.length}개
                    </p>
                  )}
                  <ConnectorLink
                    link={connector.link}
                    title={connector.title}
                    className="relative z-10"
                  />
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
