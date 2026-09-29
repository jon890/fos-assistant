import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { PRIVATE_VISIBILITY, type AdminAgent } from "@/lib/agent";

type Props = {
  agent: AdminAgent;
  currentUserId: number;
};

export function AgentCard({ agent, currentUserId }: Props) {
  return (
    <article className="rounded-md border border-border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">{agent.name}</h2>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">{agent.visibility === PRIVATE_VISIBILITY ? "나만" : "그룹 공개"}</Badge>
          {agent.visibility === PRIVATE_VISIBILITY && agent.ownerUserId !== currentUserId ? (
            <Badge variant="outline">다른 사람 것</Badge>
          ) : null}
          <Badge variant={agent.enabled ? "outline" : "default"}>{agent.enabled ? "사용 중" : "꺼짐"}</Badge>
        </div>
      </div>
      <Link href={`/agents/${agent.code}`} className="mt-3 inline-block text-sm text-primary underline-offset-4 hover:underline">
        상세 보기
      </Link>
    </article>
  );
}
