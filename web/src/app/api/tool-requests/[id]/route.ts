import { toolsetRequestRoute } from "@/lib/toolset-request-route";
import { isPublicId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

export async function GET(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!isPublicId(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "요청 주소를 확인해 주세요.",
      400,
    );
  return toolsetRequestRoute(
    request,
    `tool-requests/${encodeURIComponent(id)}`,
  );
}
