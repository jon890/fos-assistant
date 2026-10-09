"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ConnectionBadge } from "@/components/connector/connection-badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import {
  ConnectorIcon,
  ConnectorLink,
} from "@/components/connector/connector-identity";
import {
  connectorLinkHref,
  readConnectors,
  toolRiskCounts,
  type ConnectorSummary,
} from "@/lib/connection";

/** 카드 본문에 그릴 줄이 하나라도 있는지 본다. 없으면 빈 본문이 카드 아래 여백만 남기므로 본문을 그리지 않는다. */
function hasCardContent(connector: ConnectorSummary): boolean {
  return (
    !connector.available ||
    connector.tools.length > 0 ||
    connector.myStatus !== "DISCONNECTED" ||
    connectorLinkHref(connector.link) !== null
  );
}

/** 「도구 3개 · 조회 1 · 쓰기 2」 처럼 도구 수와 위험도별 수를 한 줄로 만든다. */
function toolSummary(tools: ConnectorSummary["tools"]): string {
  const parts = toolRiskCounts(tools).map(
    ({ label, count }) => `${label} ${count}`,
  );
  return [`도구 ${tools.length}개`, ...parts].join(" · ");
}

/** 연결 하나의 화면 주소다. 에이전트 화면에서 왔으면 그 에이전트를 실어 보낸다. */
function connectionHref(id: string, preferredAgent: string | null): string {
  return preferredAgent === null
    ? `/connections/${id}`
    : `/connections/${id}?agent=${preferredAgent}`;
}

/** 연결됐는데 쓰는 에이전트가 없는 카드다. 눌러 들어가면 쓸 에이전트를 고르는 영역이 열린다. */
function unused(connector: ConnectorSummary): boolean {
  return (
    connector.available &&
    connector.myStatus === "READY" &&
    connector.bindings.length === 0
  );
}

function ConnectorCard({
  connector,
  preferredAgent,
}: {
  connector: ConnectorSummary;
  preferredAgent: string | null;
}) {
  return (
    <Card
      data-testid="connector-card"
      className="relative h-full min-w-0 transition-colors duration-fast hover:bg-accent has-[[data-slot=card-link]:focus-visible]:ring-3 has-[[data-slot=card-link]:focus-visible]:ring-ring/50"
    >
      <CardHeader>
        <div className="flex min-w-0 items-start gap-3">
          <ConnectorIcon icon={connector.icon} />
          <div className="min-w-0 flex-1 space-y-1">
            <CardTitle className="min-w-0" title={connector.title}>
              <Link
                data-slot="card-link"
                prefetch={false}
                href={connectionHref(connector.id, preferredAgent)}
                className="after:absolute after:inset-0 focus-visible:outline-none"
              >
                <span className="block truncate">{connector.title}</span>
              </Link>
            </CardTitle>
            <CardDescription
              className="min-h-5 line-clamp-1 break-words"
              title={connector.description ?? undefined}
            >
              {connector.description}
            </CardDescription>
          </div>
        </div>
        <ConnectionBadge className="mt-3" status={connector.myStatus} />
      </CardHeader>
      {hasCardContent(connector) ? (
        <CardContent className="min-w-0 space-y-1">
          {connector.available ? (
            connector.tools.length > 0 ? (
              <p
                className="line-clamp-2 break-words text-sm text-muted-foreground"
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
          {connector.myStatus !== "DISCONNECTED" ? (
            <p
              className="text-sm text-muted-foreground"
              data-testid="connector-binding-count"
            >
              붙인 에이전트 {connector.bindings.length}개
            </p>
          ) : null}
          {unused(connector) ? (
            <p className="line-clamp-2 break-words text-sm text-foreground">
              아직 쓰는 에이전트가 없어요. 눌러서 쓸 에이전트를 골라 주세요.
            </p>
          ) : null}
          <ConnectorLink
            link={connector.link}
            title={connector.title}
            className="relative z-10"
          />
        </CardContent>
      ) : null}
      <CardFooter className="mt-auto">
        <Button asChild className="relative z-10 w-full">
          <Link
            data-testid="connector-action"
            prefetch={false}
            href={connectionHref(connector.id, preferredAgent)}
          >
            {connector.myStatus === "DISCONNECTED" ? "연결하기" : "연결 확인"}
            <span className="sr-only">({connector.title})</span>
          </Link>
        </Button>
      </CardFooter>
    </Card>
  );
}

export function ConnectorCatalog({
  preferredAgent,
}: {
  /** 에이전트 화면의 「외부 서비스 연결하기」 로 왔으면 그 에이전트 번호다. */
  preferredAgent: string | null;
}) {
  const [connectors, setConnectors] = useState<ConnectorSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void readConnectors().then((result) => {
      if (result.ok) setConnectors(result.data);
      else setError(result.message);
    });
  }, []);

  return (
    <div className="mx-auto w-full max-w-6xl">
      <h1 className="text-xl font-semibold">외부 서비스 연결</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">
        쓰는 서비스의 계정을 연결하면 에이전트가 그 서비스로 일을 도와요.
      </p>
      {preferredAgent !== null ? (
        <Notice variant="info" className="mb-4">
          보던 에이전트에서 쓸 서비스를 골라 연결해 주세요. 연결이 끝나면 그
          에이전트에 바로 붙일 수 있어요.
        </Notice>
      ) : null}
      {error ? (
        <Notice variant="error" role="alert">
          서비스 목록을 읽지 못했어요. {error}
        </Notice>
      ) : connectors === null ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : connectors.length === 0 ? (
        <EmptyState
          title="연결할 수 있는 서비스가 없어요"
          description="관리자가 서비스를 열면 여기에 나타나요."
        />
      ) : (
        <div className="space-y-6">
          {[
            {
              title: "연결한 서비스",
              items: connectors.filter(
                (item) => item.myStatus !== "DISCONNECTED",
              ),
            },
            {
              title: "연결할 수 있는 서비스",
              items: connectors.filter(
                (item) => item.myStatus === "DISCONNECTED",
              ),
            },
          ]
            .filter((section) => section.items.length > 0)
            .map(({ title, items }) => (
              <section key={title} aria-label={title} className="space-y-3">
                <h2 className="text-lg font-semibold">{title}</h2>
                <ul
                  data-testid="connector-grid"
                  className="grid auto-rows-fr grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3"
                >
                  {items.map((connector) => (
                    <li key={connector.id} className="min-w-0">
                      <ConnectorCard
                        connector={connector}
                        preferredAgent={preferredAgent}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))}
        </div>
      )}
    </div>
  );
}
