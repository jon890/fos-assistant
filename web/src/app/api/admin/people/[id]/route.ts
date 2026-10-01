import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

const ID = /^[1-9][0-9]*$/;

export async function PATCH(request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  if (!ID.test(id)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "사용자 번호 형식이 올바르지 않아요." },
      { status: 400 },
    );
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(`/api/v1/admin/people/${id}`, {
    method: "PATCH",
    body: parsed.body,
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
