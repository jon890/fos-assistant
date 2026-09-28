"use client";

import { PageSkeleton } from "@/components/ui/page-skeleton";
import { useShellIsAdmin } from "@/components/shell/app-shell";

export default function Loading() {
  // 레이아웃이 가진 역할로 고르면 별도 조회 없이 두 화면의 폭과 높이를 각각 맞춘다.
  const isAdmin = useShellIsAdmin();
  return isAdmin
    ? <PageSkeleton shape="cards" width="4xl" title description="agent" form="agent" />
    : <PageSkeleton shape="cards" width="2xl" title />;
}
