import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

/** 내 기기 목록이다. 주소와 키 원문 없이 이름, 등록한 날, 주소 해시만 온다. */
export async function GET() {
  const result = await callControlPlane<unknown>("/api/v1/push/devices");
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}

/** 이 브라우저의 구독을 내 기기로 등록하거나 갱신한다. 사용자는 세션이 정한다. */
export async function PUT(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane<unknown>("/api/v1/push/devices", {
    method: "PUT",
    body: parsed.body,
  });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
