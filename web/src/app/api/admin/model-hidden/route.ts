import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

/** 그룹이 숨긴 provider 와 모델을 읽는다. */
export async function GET() {
  const result = await callControlPlane<unknown>("/api/v1/admin/model-hidden");
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}

/** 관리자가 그룹의 숨김 목록을 통째로 바꾼다. */
export async function PUT(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane<unknown>("/api/v1/admin/model-hidden", {
    method: "PUT",
    body: parsed.body,
  });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
