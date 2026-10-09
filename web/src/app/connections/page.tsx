import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorCatalog } from "@/components/connector/connector-catalog";
import { agentCodeParam } from "@/lib/agent";

export default async function ConnectionsPage({
  searchParams,
}: {
  /** `agent` 는 에이전트 화면의 「외부 서비스 연결하기」 로 왔을 때 그 에이전트 번호다. */
  searchParams: Promise<{ agent?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { agent } = await searchParams;
  const preferredAgent = agentCodeParam(agent);
  return (
    <>
      {/* 사이트 로그인이 필요한 연결은 내 브라우저를 쓴다. 상단 내비게이션 대신 여기서 간다. */}
      <div className="mx-auto mb-2 flex w-full max-w-6xl justify-end">
        <Link
          href="/browser"
          className="text-sm text-foreground underline underline-offset-4 hover:text-foreground-soft"
        >
          내 브라우저
        </Link>
      </div>
      <ConnectorCatalog preferredAgent={preferredAgent} />
    </>
  );
}
