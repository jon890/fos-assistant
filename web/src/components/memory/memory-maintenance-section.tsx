import { Badge } from "@/components/ui/badge";
import type { MaintenanceCandidate } from "@/lib/memory-maintenance-api";
import { MemoryMaintenanceCandidate } from "./memory-maintenance-candidate";

/** 「정비 후보」 절이다. 후보가 없으면 그리지 않는다. */
export function MemoryMaintenanceSection({
  candidates,
  onReviewed,
}: {
  candidates: MaintenanceCandidate[];
  onReviewed(id: number): void;
}) {
  if (candidates.length === 0) return null;
  return (
    <section className="mb-8" aria-labelledby="memory-maintenance-heading">
      <div className="mb-1 flex items-center gap-2">
        <h2 id="memory-maintenance-heading" className="text-lg font-semibold">
          정비 후보
        </h2>
        <Badge variant="info">{candidates.length}</Badge>
      </div>
      <p className="mb-3 text-sm text-muted-foreground">
        오래됐거나 겹치는 기억이에요. 「맞음」 을 눌러도 기억은 바뀌지 않고 확인
        결과만 남아요.
      </p>
      <ul className="grid gap-2" aria-label="정비 후보 목록">
        {candidates.map((candidate) => (
          <MemoryMaintenanceCandidate
            key={candidate.id}
            candidate={candidate}
            onReviewed={onReviewed}
          />
        ))}
      </ul>
    </section>
  );
}
