import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { readJsonBody } from "@/lib/json-body";

/** 보관한 결과물 목록이다. 주인은 Control Plane 이 인증 사용자로 정한다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/artifact-vault");
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}

/** 대화 결과물 하나를 보관한다. 대화와 경로만 옮긴다. */
export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { conversationId, path } = parsed.body;
  if (typeof conversationId !== "string" || typeof path !== "string")
    return errorResponse(
      "VALIDATION_FAILED",
      "보관할 결과물을 확인해 주세요.",
      400,
    );
  const result = await callControlPlane("/api/v1/artifact-vault", {
    method: "POST",
    body: { conversationId, path },
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
