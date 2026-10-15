import { connectorActionResponse } from "@/lib/connector-action-route";
import { financialLimitsCall } from "@/lib/financial-limits-route";

/**
 * 로그인한 사용자의 주문 상한과 오늘 쓴 금액을 읽는다. 응답은 계약의 칸만 옮기고, 모양이 틀리면 고정 오류로 돌린다.
 * 이 경로와 바꾸는 경로는 브라우저 세션으로만 부른다. 에이전트는 상한을 MCP 도구로만 바꾸고, 올리기는 승인 카드를 거친다.
 */
export async function GET() {
  return connectorActionResponse(
    await financialLimitsCall("/api/v1/financial-limits"),
  );
}
