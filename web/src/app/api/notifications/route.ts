import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 알림 목록 한 쪽이다. `cursor` 와 `limit` 만 Control Plane 에 넘긴다. */
export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const query = new URLSearchParams();
  for (const name of ["cursor", "limit"]) {
    const value = searchParams.get(name);
    if (value !== null) query.set(name, value);
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  const result = await callControlPlane<unknown>(
    `/api/v1/notifications${suffix}`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
