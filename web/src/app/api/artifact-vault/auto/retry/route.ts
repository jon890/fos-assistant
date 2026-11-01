import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { readJsonBody } from "@/lib/json-body";

/** 자동 보관이 실패한 결과물을 다시 보관한다. 대화와 답 번호, 경로만 옮긴다. */
export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { conversationId, messageId, path } = parsed.body;
  if (
    typeof conversationId !== "string" ||
    typeof messageId !== "number" ||
    !Number.isSafeInteger(messageId) ||
    typeof path !== "string"
  )
    return errorResponse(
      "VALIDATION_FAILED",
      "다시 보관할 결과물을 확인해 주세요.",
      400,
    );
  const result = await callControlPlane("/api/v1/artifact-vault/auto/retry", {
    method: "POST",
    body: { conversationId, messageId, path },
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
