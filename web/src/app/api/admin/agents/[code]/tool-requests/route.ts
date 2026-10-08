import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { toolsetRequestRoute } from "@/lib/toolset-request-route";

export async function GET(request: Request, context: { params: Promise<{ code: string }> }) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) return errorResponse("VALIDATION_FAILED", "에이전트를 확인해 주세요.", 400);
  return toolsetRequestRoute(request, `admin/agents/${code}/tool-requests`);
}
