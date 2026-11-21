import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton } from "@/components/ui/page-state";
import { PAGE_HEADS } from "@/lib/page-head";

export default function Loading() {
  return (
    <PageFrame {...PAGE_HEADS.memory} busy>
      <ListSkeleton rows={5} />
    </PageFrame>
  );
}
