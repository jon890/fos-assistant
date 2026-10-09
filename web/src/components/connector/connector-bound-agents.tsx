import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { BoundAgent } from "@/lib/connection";

/**
 * 이 연결을 붙인 에이전트 목록이다. 떼기는 에이전트 화면에서 한다.
 * `onChoose` 가 있으면 에이전트 고르기 영역을 다시 여는 단추를 둔다.
 */
export function BoundAgents({
  bindings,
  onChoose,
}: {
  bindings: BoundAgent[];
  onChoose: (() => void) | null;
}) {
  return (
    <section className="space-y-2" data-testid="connection-bindings">
      <h2 className="text-sm font-semibold">붙인 에이전트</h2>
      {bindings.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          아직 이 연결을 쓰는 에이전트가 없어요.
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-md border border-border">
          {bindings.map((binding) => (
            <li
              key={binding.agentCode}
              className="flex flex-wrap items-center justify-between gap-2 p-3 text-sm"
              data-testid="connection-binding"
            >
              <Link
                prefetch={false}
                href={`/agents/${binding.agentCode}`}
                className="min-w-0 break-all text-foreground underline underline-offset-4"
              >
                {binding.agentName}
              </Link>
              {binding.restartRequired || binding.status === "PENDING" ? (
                <Badge variant="warning">반영 대기</Badge>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {onChoose ? (
        <Button size="sm" variant="outline" onClick={onChoose}>
          {bindings.length === 0
            ? "쓸 에이전트 고르기"
            : "다른 에이전트에도 붙이기"}
        </Button>
      ) : null}
    </section>
  );
}
