"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ConnectorAdminPanel } from "@/components/connector/connector-admin-panel";
import { useShellIsAdmin } from "@/components/shell/app-shell";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import {
  connectionStatusLabel,
  readConnectors,
  type ConnectorSummary,
} from "@/lib/connection";

export function ConnectorCatalog() {
  const isAdmin = useShellIsAdmin();
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
      <h1 className="mb-4 text-xl font-semibold">연결</h1>
      {error ? (
        <p
          role="alert"
          className="rounded-md border border-border bg-muted p-3 text-sm"
        >
          연결 목록을 읽지 못했어요. {error}
        </p>
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
              <Link
                href={`/connections/${connector.id}`}
                data-testid="connector-card"
                className="block rounded-xl focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <Card className="transition-colors hover:bg-accent">
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <CardTitle>{connector.title}</CardTitle>
                      <Badge variant="outline">
                        {connectionStatusLabel(connector.myStatus)}
                      </Badge>
                    </div>
                    {connector.description ? (
                      <CardDescription>{connector.description}</CardDescription>
                    ) : null}
                  </CardHeader>
                  {connector.available ? null : (
                    <CardContent>
                      <p className="text-sm text-muted-foreground">
                        지금은 쓸 수 없어요.
                      </p>
                    </CardContent>
                  )}
                </Card>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {isAdmin ? <ConnectorAdminPanel connectors={connectors ?? []} /> : null}
    </div>
  );
}
