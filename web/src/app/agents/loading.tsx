import { PageSkeleton } from "@/components/ui/page-skeleton";
import { readMe } from "@/lib/me";

export default async function Loading() {
  const me = await readMe();
  if (me?.role === "ADMIN") {
    return <PageSkeleton shape="cards" width="4xl" title description="agent" form="agent" />;
  }
  return <PageSkeleton shape="cards" width="2xl" title />;
}
