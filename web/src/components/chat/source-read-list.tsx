import type { SourceReadSummary } from "./message-types";

export function SourceReadList({
  sourceReads,
}: {
  sourceReads: SourceReadSummary;
}) {
  const requestedUrls = sourceReads.requestedUrls ?? [];
  const hasUrls = sourceReads.urls.length > 0;
  const hasRequestedUrls = requestedUrls.length > 0;

  if (sourceReads.completedCount === 0 && !hasUrls && !hasRequestedUrls) {
    return null;
  }

  return (
    <section
      data-testid="source-reads"
      className="mt-3 text-sm text-muted-foreground"
    >
      <h3 className="font-medium text-foreground">이번에 연 원문</h3>
      <p>
        열람 도구 완료 {sourceReads.completedCount}회 · 확인한 주소{" "}
        {sourceReads.urls.length}개
      </p>
      {hasUrls ? (
        <SourceReadUrls
          title="원문 결과에서 확인한 주소"
          urls={sourceReads.urls}
        />
      ) : null}
      {hasRequestedUrls ? (
        <>
          <SourceReadUrls
            title="열람 요청 · 도구 호출 성공"
            urls={requestedUrls}
          />
          <p>페이지별 성공은 확인하지 못했어요.</p>
        </>
      ) : null}
      {!hasUrls && !hasRequestedUrls ? (
        <p>기록에서 확인한 원문 주소가 없어요.</p>
      ) : null}
      {sourceReads.unresolvedCount > 0 ? (
        <p>일부 열람 결과에서 주소를 확인하지 못했어요.</p>
      ) : null}
      {!sourceReads.observationComplete ? (
        <p>열람 기록을 모두 확인하지 못했어요.</p>
      ) : null}
    </section>
  );
}

function SourceReadUrls({ title, urls }: { title: string; urls: string[] }) {
  return (
    <section>
      <h4 className="font-medium text-foreground">{title}</h4>
      <ul className="list-disc pl-5">
        {urls.map((url) => (
          <li key={url}>
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
  );
}
