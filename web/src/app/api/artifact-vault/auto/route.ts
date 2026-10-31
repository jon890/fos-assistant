import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { readJsonBody } from "@/lib/json-body";

/** 결과물 자동 보관을 켰는지다. 사용자는 Control Plane 이 인증 사용자로 정한다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/artifact-vault/auto");
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}

/** 결과물 자동 보관을 켜거나 끈다. 참거짓 하나만 옮긴다. */
export async function PUT(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { enabled } = parsed.body;
  if (typeof enabled !== "boolean")
    return errorResponse(
      "VALIDATION_FAILED",
      "자동 보관 설정을 확인해 주세요.",
      400,
    );
  const result = await callControlPlane("/api/v1/artifact-vault/auto", {
    method: "PUT",
    body: { enabled },
  });
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
