import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 그룹 전체의 에이전트 브라우저 조작 스위치다. 권한은 Control Plane 이 확인한다. */
export async function GET() {
  const result = await callControlPlane("/api/v1/admin/browsers/agent-control");
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}

/** 관리자. 그룹 전체 스위치를 바꾼다. 본문은 `{ enabled }` 다. */
export async function PUT(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(
    "/api/v1/admin/browsers/agent-control",
    {
      method: "PUT",
      body: parsed.body,
    },
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
