import Link from "next/link";
import { EmptyState } from "@/components/ui/empty-state";
import { formatWhen } from "@/lib/format";
import type { SkillUsageRow } from "@/lib/skill";

function SkillUsageContent({ row }: { row: SkillUsageRow }) {
  return (
    <>
      <span className="min-w-0">
        <span className="block truncate font-medium">{row.skillName}</span>
        <span className="block truncate text-xs text-muted-foreground">
          {row.agentName}
        </span>
      </span>
      <span className="shrink-0 text-right text-sm">
        <span className="block font-medium">
          {row.count.toLocaleString("ko-KR")}회
        </span>
        <span className="block text-xs text-muted-foreground">
          마지막 호출 {formatWhen(row.lastInvokedAt)}
        </span>
      </span>
    </>
  );
}

/**
 * 내가 부른 스킬을 에이전트와 스킬마다 한 줄씩 보인다.
 *
 * <p>마지막 호출이 속한 대화가 남아 있으면 줄을 눌러 그 대화로 간다. 지운 대화면 링크 없이 보이기만 한다.
 */
export function SkillUsageList({ rows }: { rows: SkillUsageRow[] }) {
  if (rows.length === 0) {
    return (
      <EmptyState
        title="아직 부른 스킬이 없어요."
        description="에이전트가 스킬을 읽으면 여기에 쌓여요."
      />
    );
  }

  const rowClass =
    "flex items-center justify-between gap-3 rounded-md border border-border px-4 py-3";
  return (
    <section aria-label="스킬 호출">
      <ul className="grid gap-3" data-testid="skill-usage-list">
        {rows.map((row, index) => (
          // 에이전트 코드는 관리자에게만 와서 key 로 쓰지 않는다. 이름이 같은 에이전트가 있어도 겹치지 않게 순번을 붙인다.
          <li key={`${row.agentName}/${row.skillName}/${index}`}>
            {row.lastConversationId ? (
              <Link
                prefetch={false}
                href={`/chat/${row.lastConversationId}`}
                className={`${rowClass} hover:bg-muted`}
              >
                <SkillUsageContent row={row} />
              </Link>
            ) : (
              <div className={rowClass}>
                <SkillUsageContent row={row} />
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
