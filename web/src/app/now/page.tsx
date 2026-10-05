import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { NowScreen } from "@/components/now/now-screen";

export const metadata: Metadata = { title: "지금 볼 것" };

export default async function NowPage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return <NowScreen />;
}
