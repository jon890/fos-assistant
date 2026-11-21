import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton } from "@/components/ui/page-state";
import { PAGE_HEADS } from "@/lib/page-head";

export default function Loading() {
  return (
    <PageFrame
      title=""
      titlePending
      busy
      back={{ href: "/agents", label: PAGE_HEADS.agents.title }}
    >
      <ListSkeleton />
    </PageFrame>
  );
}
