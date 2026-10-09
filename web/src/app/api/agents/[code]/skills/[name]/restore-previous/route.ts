import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { SKILL_NAME_PATTERN } from "@/lib/skill";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ code: string; name: string }> };

/**
 * 올린 스킬의 지금 버전과 이전 버전을 맞바꾼다. 응답은 맞바꾼 뒤의 스킬 상세다.
 * 경로 검사는 `[name]/route.ts` 와 같다. Next.js 는 route 파일이 핸들러 밖의 이름을 내보내지 못하게 해 검사를 따로 둔다.
 */
export async function POST(_request: Request, context: RouteContext) {
  const { code, name } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "에이전트 코드 형식이 올바르지 않아요.",
      400,
    );
  }
  if (!SKILL_NAME_PATTERN.test(name)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "스킬 이름 형식이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(
    `/api/v1/agents/${code}/skills/${name}/restore-previous`,
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
