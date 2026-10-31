import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

/** 정비 후보를 「맞음」(CORRECT) 이나 「아님」(INCORRECT) 으로 확인한다. 기억은 바뀌지 않고 성공하면 본문 없이 204 다. */
export async function PUT(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id))
    return errorResponse(
      "VALIDATION_FAILED",
      "후보 번호가 올바르지 않아요.",
      400,
    );
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { review } = parsed.body;
  if (review !== "CORRECT" && review !== "INCORRECT")
    return errorResponse(
      "VALIDATION_FAILED",
      "확인 결과가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/memory/maintenance-candidates/${Number(id)}/review`,
    { method: "PUT", body: { review } },
  );
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : errorResponse(result.code, result.message, result.status);
}
