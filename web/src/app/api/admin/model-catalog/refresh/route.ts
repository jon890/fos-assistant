import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 켜진 모든 profile 의 모델 목록을 Hermes 에 다시 묻게 한다. 관리자의 「목록 새로 읽기」 가 쓴다. */
export async function POST() {
  const result = await callControlPlane<unknown>(
    "/api/v1/admin/model-catalog/refresh",
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
