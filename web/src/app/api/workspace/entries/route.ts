import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 디렉터리 한 페이지다. `path`와 `cursor`만 옮긴다. 주인은 Control Plane이 인증 사용자로 정한다. */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const forwarded = new URLSearchParams();
  for (const key of ["path", "cursor"]) {
    const value = params.get(key);
    if (value !== null) forwarded.set(key, value);
  }
  const query = forwarded.size === 0 ? "" : `?${forwarded}`;
  const result = await callControlPlane(`/api/v1/workspace/entries${query}`);
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}

/** 경로 하나를 지운다. 빈 경로는 사용자 디렉터리 자체라 Control Plane 에 보내지 않고 막는다. */
export async function DELETE(request: Request) {
  const path = new URL(request.url).searchParams.get("path");
  if (path === null || path === "")
    return errorResponse(
      "VALIDATION_FAILED",
      "지울 경로를 확인해 주세요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/workspace/entries?path=${encodeURIComponent(path)}`,
    { method: "DELETE" },
  );
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
