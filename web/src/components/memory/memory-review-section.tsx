import { Badge } from "@/components/ui/badge";
import type { SupersedeProposal } from "@/lib/memory-link-api";
import type { Memory } from "./memory-list";
import { MemoryProposal } from "./memory-proposal";
import { MemorySupersedeProposal } from "./memory-supersede-proposal";

/** 「검토할 기억」 절이다. 제안이 없으면 그리지 않는다. */
export function MemoryReviewSection({
  proposals,
  supersedes,
  openId,
  readAt,
  onToggle,
  onChanged,
}: {
  proposals: Memory[];
  supersedes: SupersedeProposal[];
  openId: number | null;
  readAt: string;
  onToggle(id: number): void;
  onChanged(): Promise<void>;
}) {
  if (proposals.length + supersedes.length === 0) return null;
  return (
    <section className="mb-8" aria-labelledby="memory-review-heading">
      <div className="mb-1 flex items-center gap-2">
        <h2 id="memory-review-heading" className="text-lg font-semibold">
          검토할 기억
        </h2>
        <Badge variant="info">{proposals.length + supersedes.length}</Badge>
      </div>
      <p className="mb-3 text-sm text-muted-foreground">
        에이전트가 기억해도 될지 물어본 것이에요. 눌러서 내용을 보고
        받아들이거나 거절해 주세요.
      </p>
      <ul className="grid gap-2">
        {supersedes.map((proposal) => (
          <MemorySupersedeProposal
            key={`link-${proposal.id}`}
            proposal={proposal}
            onChanged={onChanged}
          />
        ))}
        {proposals.map((memory) => (
          <MemoryProposal
            key={memory.id}
            memory={memory}
            open={openId === memory.id}
            readAt={readAt}
            onToggle={() => onToggle(memory.id)}
            onChanged={onChanged}
          />
        ))}
      </ul>
    </section>
  );
}
