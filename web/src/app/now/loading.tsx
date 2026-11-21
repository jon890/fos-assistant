import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton } from "@/components/ui/page-state";
import { Skeleton } from "@/components/ui/skeleton";
import { PAGE_HEADS } from "@/lib/page-head";

export default function Loading() {
  return (
    <PageFrame {...PAGE_HEADS.now} busy>
      {/* 탭 줄(TabsList 의 p-1 과 min-h-9)과 탭 아래 간격(gap-4)의 자리다. */}
      <Skeleton className="mb-4 h-11 w-48 rounded-lg" />
      <ListSkeleton />
    </PageFrame>
  );
}
