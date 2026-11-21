import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorConnectionPanel } from "@/components/connector/connector-connection-panel";
import { agentCodeParam } from "@/lib/agent";
import {
  OAUTH_REASON_PARAM,
  OAUTH_RESULT_PARAM,
  isOAuthOutcome,
  isOAuthReason,
} from "@/lib/connection-oauth";
import { readMe } from "@/lib/me";

type Query = Record<string, string | string[] | undefined>;

export default async function ConnectionPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  /**
   * `agent` 는 에이전트 화면에서 이 연결을 하러 왔을 때 그 에이전트 번호다.
   * `oauth` 와 `reason` 은 단추 연결의 돌아오는 화면이 넘긴 결과다.
   */
  searchParams: Promise<Query>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { id } = await params;
  const query = await searchParams;
  const preferredAgent = agentCodeParam(query.agent);
  const outcome = query[OAUTH_RESULT_PARAM];
  const reason = query[OAUTH_REASON_PARAM];
  // 역할을 읽지 못하면 관리자 안내만 빠진다. 단추와 입력 칸은 그대로 보인다.
  const me = await readMe();
  return (
    <ConnectorConnectionPanel
      id={id}
      preferredAgent={preferredAgent}
      admin={me?.role === "ADMIN"}
      oauthResult={
        isOAuthOutcome(outcome)
          ? { outcome, reason: isOAuthReason(reason) ? reason : null }
          : null
      }
    />
  );
}
