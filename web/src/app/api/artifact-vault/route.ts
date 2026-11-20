import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { readJsonBody } from "@/lib/json-body";

/** 목록 인자 가운데 Control Plane 에 넘기는 것이다. 값의 검사는 Control Plane 이 한다. */
const LIST_PARAMS = ["kind", "q", "conversation", "before", "limit"];

/** 「내 파일」 목록 한 쪽이다. 주인은 Control Plane 이 인증 사용자로 정한다. */
export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const query = new URLSearchParams();
  for (const name of LIST_PARAMS) {
    const value = searchParams.get(name);
    if (value !== null && value !== "") query.set(name, value);
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  const result = await callControlPlane(`/api/v1/artifact-vault${suffix}`);
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
