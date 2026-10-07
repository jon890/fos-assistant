import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorCatalog } from "@/components/connector/connector-catalog";

export default async function ConnectionsPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  return (
    <>
      {/* 사이트 로그인이 필요한 연결은 내 브라우저를 쓴다. 상단 내비게이션 대신 여기서 간다. */}
      <div className="mx-auto mb-2 flex w-full max-w-2xl justify-end">
        <Link
          href="/browser"
          className="text-sm text-foreground underline underline-offset-4 hover:text-foreground-soft"
        >
          내 브라우저
        </Link>
      </div>
      <ConnectorCatalog />
    </>
  );
}
