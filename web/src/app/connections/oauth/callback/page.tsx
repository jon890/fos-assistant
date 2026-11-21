import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { OAuthCallback } from "@/components/connector/oauth-callback";

type Query = Record<string, string | string[] | undefined>;

function one(value: string | string[] | undefined): string | null {
  return typeof value === "string" ? value : null;
}

/** 서비스 로그인·허락 화면에서 돌아오는 주소다. 검색어를 Control Plane 에 넘기고 연결 화면으로 간다. */
export default async function OAuthCallbackPage({
  searchParams,
}: {
  searchParams: Promise<Query>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const query = await searchParams;
  return (
    <OAuthCallback
      query={{
        state: one(query.state),
        code: one(query.code),
        error: one(query.error),
        setupAction: one(query.setup_action),
      }}
    />
  );
}
