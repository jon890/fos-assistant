import { Notice } from "@/components/ui/notice";
import { redirect } from "next/navigation";
import { requestControlPlane } from "@/lib/control-plane";
import type { ToolsetRequest } from "@/lib/toolset-request";
import { isPublicId } from "@/lib/conversation-id";
import { ToolsetRequestDecision } from "./toolset-request-decision";

/** 요청자 결과 화면과 관리자 승인 화면은 각각의 권한 경로로 읽는다. */
export async function loadToolsetRequest(id: string, admin: boolean) {
  if (!isPublicId(id))
    return (
      <Notice variant="error" role="alert">
        요청 주소를 확인해 주세요.
      </Notice>
    );
  const opened = await requestControlPlane(
    `/api/v1/${admin ? "admin/" : ""}tool-requests/${encodeURIComponent(id)}`,
  );
  if (!opened.ok || !opened.response.ok) {
    return (
      <Notice variant="error" role="alert">
        이 사용 요청을 읽을 수 없어요. 계정과 요청을 확인해 주세요.
      </Notice>
    );
  }
  const row = (await opened.response.json()) as ToolsetRequest;
  if (admin && !row.agentDeleted) redirect(`/admin/agents/${row.agentCode}`);
  return <ToolsetRequestDecision initial={row} admin={admin} />;
}
