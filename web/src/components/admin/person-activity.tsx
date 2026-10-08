import { formatRelative } from "@/lib/format";

type Props = {
  value: string | null;
  readAt: string;
};

function fullTimeWithSeconds(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(date);
}

/** 관리자 목록과 상세가 같은 기준 시각으로 그리는 최근 활동 시각이다. */
export function PersonActivity({ value, readAt }: Props) {
  if (!value) return <>기록 없음</>;
  return (
    <time dateTime={value} className="block" title={`${fullTimeWithSeconds(value)} 서울 시각`}>
      <span>{formatRelative(value, new Date(readAt))}</span>
      <span className="mt-0.5 block text-xs text-muted-foreground">
        {fullTimeWithSeconds(value)} 서울 시각
      </span>
    </time>
  );
}
