import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { MemoryList, type Memory } from "@/components/memory/memory-list";
import { callControlPlane } from "@/lib/control-plane";
import { readMe } from "@/lib/me";

export default async function MemoryPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const [result, me] = await Promise.all([callControlPlane<Memory[]>("/api/v1/memories"), readMe()]);
  if (!result.ok) return <p className="text-sm">{result.message}</p>;
  return <MemoryList initialMemories={result.data} isAdmin={me?.role === "ADMIN"} currentUserId={me?.id} />;
}
