import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { OverviewRead } from "./use-overview-read";

/**
 * 요약 카드 하나의 틀이다. 제목 옆에 「그룹 전체」 를 적고, 읽는 중은 뼈대, 실패는 그 카드 안에서만 안내와 다시 읽기를 그린다.
 *
 * <p>실패 응답의 오류 코드와 메시지는 그리지 않는다.
 */
export function OverviewCard<T>({
  title,
  testId,
  read,
  onRetry,
  link,
  children,
}: {
  title: string;
  testId: string;
  read: OverviewRead<T>;
  onRetry: () => void;
  link: { href: string; label: string };
  children: (value: T) => React.ReactNode;
}) {
  return (
    <Card data-testid={testId} aria-label={title} role="region">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <h2 className="text-base font-semibold">{title}</h2>
          <Badge variant="outline">그룹 전체</Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        {read.state === "loading" ? (
          <div aria-busy="true" className="flex flex-col gap-2">
            <Skeleton className="h-6 w-24" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        ) : read.state === "failed" ? (
          <div role="alert" className="flex flex-col items-start gap-2">
            <p>불러오지 못했어요</p>
            <Button variant="outline" size="sm" onClick={onRetry}>
              다시 읽기
            </Button>
          </div>
        ) : (
          children(read.value)
        )}
        <Link
          href={link.href}
          className="self-start text-sm text-muted-foreground underline underline-offset-4 hover:text-foreground"
        >
          {link.label}
        </Link>
      </CardContent>
    </Card>
  );
}
