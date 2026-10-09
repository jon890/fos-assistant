import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { errorResponse } from "@/lib/api-response";
import { forwardMultipart } from "@/lib/forward-multipart";

type RouteContext = { params: Promise<{ code: string }> };

/** 스킬 zip 묶음을 올린다. multipart 본문(`file`, 선택 `baseDigest`)을 그대로 흘려보낸다. */
export async function POST(request: Request, context: RouteContext) {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "에이전트 코드 형식이 올바르지 않아요.",
      400,
    );
  }
  return forwardMultipart(
    `/api/v1/agents/${code}/skill-packages`,
    request,
    "zip 파일이 너무 커요.",
  );
}
