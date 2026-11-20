import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 브라우저 중계 서명 비밀을 서버가 새로 만들고 바인딩 다시 설치 결과를 돌려준다. 본문이 없다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const result = await callControlPlane(
    "/api/v1/admin/secrets/browser-gateway/regenerate",
    { method: "POST" },
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
