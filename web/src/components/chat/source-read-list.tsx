import type { SourceReadSummary } from "./message-types";

export function SourceReadList({ sourceReads }: { sourceReads: SourceReadSummary }) {
  return <section data-testid="source-reads" className="mt-3 text-sm text-muted-foreground">
    <h3 className="font-medium text-foreground">이번에 연 원문</h3>
    <p>열람 도구 완료 {sourceReads.completedCount}회 · 확인한 주소 {sourceReads.urls.length}개</p>
    {sourceReads.urls.length === 0 ? <p>기록에서 확인한 원문 주소가 없어요.</p> : <ul className="list-disc pl-5">{sourceReads.urls.map((url) => <li key={url}><a href={url} target="_blank" rel="noreferrer noopener" className="underline">{url}</a></li>)}</ul>}
    {sourceReads.unresolvedCount > 0 ? <p>일부 열람 결과에서 주소를 확인하지 못했어요.</p> : null}
    {!sourceReads.observationComplete ? <p>열람 기록을 모두 확인하지 못했어요.</p> : null}
  </section>;
}
