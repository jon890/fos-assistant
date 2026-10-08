import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

/** 먼저 다룰 문제 하나에 반응을 남긴다. 본문의 `reaction` 만 넘기고, 성공하면 본문 없이 204 다. */
export async function PUT(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "판정 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/autonomy-decisions/${id}/reaction`,
    {
      method: "PUT",
      body: { reaction: parsed.body.reaction },
    },
  );
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : errorResponse(result.code, result.message, result.status);
}
