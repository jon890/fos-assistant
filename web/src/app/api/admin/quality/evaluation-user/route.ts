import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { readJsonBody } from "@/lib/json-body";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 평가 전용 사용자가 준비됐는지와 빠진 것이다. */
export async function GET() {
  const result = await callControlPlane(
    "/api/v1/admin/quality/evaluation-user",
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}

/** 관리자. 평가 전용 사용자를 준비한다. 다시 불러도 빠진 것만 채운다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane(
    "/api/v1/admin/quality/evaluation-user",
    {
      method: "POST",
      body: parsed.body,
    },
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
