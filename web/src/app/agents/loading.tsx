import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton } from "@/components/ui/page-state";
import { PAGE_HEADS } from "@/lib/page-head";

export default function Loading() {
  return (
    <PageFrame {...PAGE_HEADS.agents} busy>
      <ListSkeleton />
    </PageFrame>
  );
}
