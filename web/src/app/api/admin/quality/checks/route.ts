import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 배포 뒤 확인을 최근 순으로 한 쪽 읽는다. 권한은 Control Plane 이 확인한다. */
export async function GET(request: Request) {
  const cursor = new URL(request.url).searchParams.get("cursor");
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  const result = await callControlPlane(`/api/v1/admin/quality/checks${query}`);
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}

/** 관리자. 확인을 시작한다. 새로 시작하면 202, 이미 도는 확인을 돌려주면 200 이라 상태 코드를 그대로 넘긴다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const result = await callControlPlane("/api/v1/admin/quality/checks", {
    method: "POST",
  });
  return result.ok
    ? NextResponse.json(result.data, { status: result.status })
    : errorResponse(result.code, result.message, result.status);
}
