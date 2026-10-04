import Link from "next/link";
import { Fragment, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import {
  itemHref,
  originText,
  reasonText,
  type AttentionItem,
} from "@/lib/attention";
import {
  executionStatusLabel,
  executionStatusVariant,
} from "@/lib/execution-status";
import { formatFullTime, formatRelative } from "@/lib/format";

/**
 * 지금 화면의 항목 한 줄이다. 제목은 모델이 쓴 글일 수 있어 평문으로만 그린다(ADR-009).
 *
 * @param readAt 응답을 읽은 시각. 서버에서 그린 글과 브라우저에서 다시 그린 글이 같도록 지금 시각 대신 쓴다
 */
export function NowItem({
  item,
  readAt,
}: {
  item: AttentionItem;
  readAt: string;
}) {
  const href = itemHref(item);
  const title = item.title || "새 대화";
  const origin = originText(item);
  const sources: ReactNode[] = [
    ...(item.agentName ? [item.agentName] : []),
    ...(origin ? [origin] : []),
    <time key="at" dateTime={item.at} title={formatFullTime(item.at)}>
      {formatRelative(item.at, new Date(readAt))}
    </time>,
  ];
  // 판정 응답에는 오류 코드가 없다. 상태만으로 문구와 색을 고른다.
  const execution = item.execution
    ? { status: item.execution.status, errorCode: null }
    : null;

  return (
    <li
      data-testid="now-item"
      data-attention={item.attention}
      className="flex flex-col gap-1"
    >
      <div className="flex flex-wrap items-start gap-2">
        {href ? (
          <Link
            href={href}
            prefetch={false}
            className="min-w-0 flex-1 font-medium break-words hover:underline"
          >
            {title}
          </Link>
        ) : (
          <span className="min-w-0 flex-1 font-medium break-words">
            {title}
          </span>
        )}
        {item.attention === "NOW" ? (
          <Badge variant="warning">지금</Badge>
        ) : null}
        {execution ? (
          <Badge variant={executionStatusVariant(execution)}>
            {executionStatusLabel(execution, false)}
          </Badge>
        ) : null}
      </div>
      <p className="text-sm text-muted-foreground">{reasonText(item.why)}</p>
      <p className="flex flex-wrap gap-x-1 text-xs text-muted-foreground">
        {sources.map((source, index) => (
          <Fragment key={index}>
            {index > 0 ? <span>·</span> : null}
            <span className="min-w-0 break-words">{source}</span>
          </Fragment>
        ))}
      </p>
    </li>
  );
}
