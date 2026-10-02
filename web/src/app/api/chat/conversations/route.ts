import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

/** 대화 목록 한 쪽이다. `cursor` 와 `limit` 만 Control Plane 에 넘긴다. */
export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const query = new URLSearchParams();
  for (const name of ["cursor", "limit"]) {
    const value = searchParams.get(name);
    if (value !== null) query.set(name, value);
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  const result = await callControlPlane<unknown>(
    `/api/v1/chat/conversations${suffix}`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}

/** 제목이 빈 대화를 만든다. 사진을 먼저 올리려면 대화 번호가 먼저 있어야 한다. */
export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body as { agentCode?: string };

  const result = await callControlPlane<{ conversationId: string }>(
    "/api/v1/chat/conversations",
    {
      method: "POST",
      body: { agentCode: body.agentCode ?? null },
    },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
