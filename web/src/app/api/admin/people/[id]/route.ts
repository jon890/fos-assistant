import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

const ID = /^[1-9][0-9]*$/;

export async function PATCH(request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  if (!ID.test(id)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "사람 번호 형식이 올바르지 않습니다." },
      { status: 400 },
    );
  }
  const result = await callControlPlane(`/api/v1/admin/people/${id}`, {
    method: "PATCH",
    body: await request.json(),
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
