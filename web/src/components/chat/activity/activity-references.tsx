import Link from "next/link";
import type { MemoryUse } from "@/lib/memory-use-api";
import type { SourceReadSummary } from "@/components/chat/message-types";

const VIA_LABELS: Record<MemoryUse["via"], string> = {
  ALWAYS: "항상",
  FACTS: "기억한 사실",
  SEARCH: "질문으로 찾은 기억",
  READ: "찾아 읽음",
};

/** 결과에서 확인한 주소와 요청 주소를 한 목록으로 합친다. 같은 주소는 한 번만 둔다. */
function sourceUrls(sourceReads: SourceReadSummary | null | undefined) {
  if (!sourceReads) return [];
  return [
    ...new Set([...sourceReads.urls, ...(sourceReads.requestedUrls ?? [])]),
  ];
}

/**
 * 끝난 답의 작업 과정 맨 끝에 그 답이 연 원문과 본문을 받은 기억을 작게 둔다. 둘 다 없으면 아무것도 그리지 않는다.
 *
 * <p>사용자가 확인할 내용이 아니라 작업 과정의 일부라 접힌 줄에는 개수를 더하지 않는다.
 * 기억 제목은 사용자나 모델이 쓴 글이라 평문으로만 그린다(ADR-009).
 */
export function ActivityReferences({
  sourceReads,
  memoryUses,
}: {
  sourceReads?: SourceReadSummary | null;
  memoryUses?: MemoryUse[];
}) {
  const urls = sourceUrls(sourceReads);
  const uses = memoryUses ?? [];
  if (urls.length === 0 && uses.length === 0) return null;
  return (
    <div className="mt-2 flex min-w-0 flex-col gap-2 border-t border-border pt-2 text-xs text-muted-foreground">
      {urls.length > 0 ? (
        <section data-testid="activity-source-reads">
          <h4 className="font-medium text-foreground-soft">연 원문</h4>
          <ul className="list-disc pl-5">
            {urls.map((url) => (
              <li key={url} className="break-all">
                <a
                  href={url}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="underline"
                >
                  {url}
                </a>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {uses.length > 0 ? (
        <section data-testid="activity-memory-uses">
          <h4 className="font-medium text-foreground-soft">참고한 기억</h4>
          <ul className="flex flex-col gap-1">
            {uses.map((use) => (
              <li
                key={`${use.via}:${use.memoryId}`}
                data-testid="activity-memory-use"
                className="flex flex-wrap items-baseline gap-x-2"
              >
                <span className="min-w-0 break-words text-foreground-soft">
                  {use.title}
                </span>
                <span>{VIA_LABELS[use.via]}</span>
                {use.scope === "GROUP" ? <span>그룹</span> : null}
              </li>
            ))}
          </ul>
          <Link
            href="/memory"
            prefetch={false}
            className="underline underline-offset-2"
          >
            기억 화면에서 고치기
          </Link>
        </section>
      ) : null}
    </div>
  );
}
