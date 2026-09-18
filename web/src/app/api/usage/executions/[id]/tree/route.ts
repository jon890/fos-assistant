import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

function invalid() {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "실행 번호가 올바르지 않다." },
    { status: 400 },
  );
}

/**
 * 실행 하나가 속한 나무를 낸다.
 *
 * <p>브라우저는 Control Plane 토큰을 갖지 않으므로 이 서버 라우트가 세션에서 토큰을 만들어 대신
 * 부른다.
 */
export async function GET(_request: Request, context: { params: Promise<{ id: string }> }) {
  const { id } = await context.params;
  if (!/^\d+$/.test(id)) return invalid();

  const result = await callControlPlane<unknown>(`/api/v1/usage/executions/${id}/tree`);
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
