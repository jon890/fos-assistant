import type { Metadata } from "next";
import { AdminOverview } from "@/components/admin/admin-overview";

export const metadata: Metadata = { title: "요약" };

/** 관리자 영역의 첫 화면(요약)이다. 이름 메뉴의 「관리자 화면」 이 여기로 온다. */
export default function AdminPage() {
  return <AdminOverview />;
}
