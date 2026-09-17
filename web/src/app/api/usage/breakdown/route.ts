import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

/**
 * 축별 합계를 Control Plane 에서 받아 온다.
 *
 * 브라우저가 보낸 것 가운데 축과 달만 넘긴다. 누구의 합계인지는 세션이 정하므로
 * 요청을 고쳐 남의 자료를 달라고 할 수 없다.
 */
export async function GET(request: Request) {
  const asked = new URL(request.url).searchParams;
  const query = new URLSearchParams({ axis: asked.get("axis") ?? "" });
  const month = asked.get("month");
  if (month) query.set("month", month);

  const result = await callControlPlane<unknown>(`/api/v1/usage/breakdown?${query.toString()}`);
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
