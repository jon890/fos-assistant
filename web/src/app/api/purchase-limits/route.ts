import { connectorActionResponse } from "@/lib/connector-action-route";
import { purchaseLimitsCall } from "@/lib/purchase-limits-route";

/**
 * 로그인한 사용자의 구매 상한과 오늘 승인한 합계를 읽는다. 응답은 계약의 칸만 옮기고, 모양이 틀리면 고정 오류로 돌린다.
 * 이 경로와 바꾸는 경로는 브라우저 세션으로만 부른다. 에이전트는 구매 상한을 바꾸지 못한다.
 */
export async function GET() {
  return connectorActionResponse(
    await purchaseLimitsCall("/api/v1/purchase-limits"),
  );
}
